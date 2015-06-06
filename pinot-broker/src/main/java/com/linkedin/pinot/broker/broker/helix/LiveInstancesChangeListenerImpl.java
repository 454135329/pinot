/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.broker.broker.helix;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.helix.LiveInstanceChangeListener;
import org.apache.helix.NotificationContext;
import org.apache.helix.model.LiveInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.pinot.common.response.ServerInstance;
import com.linkedin.pinot.common.utils.CommonConstants;
import com.linkedin.pinot.transport.common.KeyedFuture;
import com.linkedin.pinot.transport.netty.NettyClientConnection;
import com.linkedin.pinot.transport.pool.KeyedPool;


public class LiveInstancesChangeListenerImpl implements LiveInstanceChangeListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(LiveInstancesChangeListenerImpl.class);

  private long timeout;
  private final Map<String, String> liveInstanceToSessionIdMap;
  private KeyedPool<ServerInstance, NettyClientConnection> connectionPool;

  public LiveInstancesChangeListenerImpl(String clusterName) {
    this.liveInstanceToSessionIdMap = new HashMap<String, String>();
  }

  public void init(final KeyedPool<ServerInstance, NettyClientConnection> connectionPool, final long timeout) {
    this.connectionPool = connectionPool;
    this.timeout = timeout;
  }

  @Override
  public void onLiveInstanceChange(List<LiveInstance> liveInstances, NotificationContext changeContext) {
    if (connectionPool == null) {
      LOGGER.info("init hasn't been called yet on the live instances listener...");
      return;
    }

    LOGGER.info("Connection pool found, moving on...");

    for (LiveInstance instance : liveInstances) {

      LOGGER.info("working on instance : " + instance.getInstanceName());
      String instanceId = instance.getInstanceName();
      String sessionId = instance.getSessionId();

      if (instanceId.startsWith("Broker_")) {
        LOGGER.info("skipping broker instances {}", instanceId);
        continue;
      }

      LOGGER.info("found instance Id : {} with session Id : {}", instanceId, sessionId);

      String namePortStr = instanceId.split("Server_")[1];
      String hostName = namePortStr.split("_")[0];
      int port;
      try {
        port = Integer.parseInt(namePortStr.split("_")[1]);
      } catch (Exception e) {
        port = CommonConstants.Helix.DEFAULT_SERVER_NETTY_PORT;
      }
      ServerInstance ins = new ServerInstance(hostName, port);

      if (liveInstanceToSessionIdMap.containsKey(instanceId)) {
        // sessionId has changed
        if (!sessionId.equals(liveInstanceToSessionIdMap.get(instanceId))) {
          try {
            KeyedFuture<ServerInstance, NettyClientConnection> c = connectionPool.checkoutObject(ins);
            // destroy all existing connection
            for (Map.Entry<ServerInstance, NettyClientConnection> entry : c.get().entrySet()) {
              if (!entry.getValue().validate()) {
                connectionPool.destroyObject(ins, entry.getValue());
              }
            }
            liveInstanceToSessionIdMap.put(instanceId, sessionId);
          } catch (Exception e) {
            LOGGER.error("error trying to destroy dead connections : {} ", e);
          }
        }
      } else {
        // we don't have this instanceId
        // lets first check if the connection is valid or not
        try {
          KeyedFuture<ServerInstance, NettyClientConnection> c = connectionPool.checkoutObject(ins);
          NettyClientConnection conn = c.getOne(timeout, TimeUnit.MILLISECONDS);
          if (conn != null) {
            if (!conn.validate()) {
              for (Map.Entry<ServerInstance, NettyClientConnection> entry : c.get().entrySet()) {
                if (!entry.getValue().validate()) {
                  connectionPool.destroyObject(ins, entry.getValue());
                }
              }
            }
            liveInstanceToSessionIdMap.put(instanceId, sessionId);
          }
        } catch (Exception e) {
          LOGGER.error("error trying to destroy dead connections : {}", e);
        }
      }
    }
  }
}

package com.linkedin.thirdeye.dashboard.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.thirdeye.anomaly.detection.AnomalyDetectionInputContext;
import com.linkedin.thirdeye.anomaly.detection.AnomalyDetectionInputContextBuilder;
import com.linkedin.thirdeye.anomalydetection.context.AnomalyFeedback;
import com.linkedin.thirdeye.api.DimensionMap;
import com.linkedin.thirdeye.api.MetricTimeSeries;
import com.linkedin.thirdeye.api.TimeGranularity;
import com.linkedin.thirdeye.api.TimeSpec;
import com.linkedin.thirdeye.constant.AnomalyFeedbackType;
import com.linkedin.thirdeye.constant.MetricAggFunction;
import com.linkedin.thirdeye.datalayer.bao.AnomalyFunctionManager;
import com.linkedin.thirdeye.datalayer.bao.AutotuneConfigManager;
import com.linkedin.thirdeye.datalayer.bao.EmailConfigurationManager;
import com.linkedin.thirdeye.datalayer.bao.MergedAnomalyResultManager;
import com.linkedin.thirdeye.datalayer.bao.RawAnomalyResultManager;
import com.linkedin.thirdeye.datalayer.dto.AnomalyFunctionDTO;
import com.linkedin.thirdeye.datalayer.dto.AutotuneConfigDTO;
import com.linkedin.thirdeye.datalayer.dto.DatasetConfigDTO;
import com.linkedin.thirdeye.datalayer.dto.EmailConfigurationDTO;
import com.linkedin.thirdeye.datalayer.dto.MergedAnomalyResultDTO;
import com.linkedin.thirdeye.datalayer.dto.RawAnomalyResultDTO;
import com.linkedin.thirdeye.datasource.DAORegistry;
import com.linkedin.thirdeye.datasource.ThirdEyeCacheRegistry;
import com.linkedin.thirdeye.detector.function.AnomalyFunction;
import com.linkedin.thirdeye.detector.function.AnomalyFunctionFactory;
import com.linkedin.thirdeye.detector.function.BaseAnomalyFunction;

import com.linkedin.thirdeye.util.ThirdEyeUtils;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.linkedin.thirdeye.anomaly.detection.lib.AutotuneMethodType.*;


@Path("dashboard/anomaly-function")
@Produces(MediaType.APPLICATION_JSON)
public class AnomalyFunctionResource {

  private static final Logger LOG = LoggerFactory.getLogger(AnomalyFunctionResource.class);

  private static final ThirdEyeCacheRegistry CACHE_REGISTRY_INSTANCE = ThirdEyeCacheRegistry.getInstance();
  private static final DAORegistry DAO_REGISTRY = DAORegistry.getInstance();
  private static ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final Map<String, Object> anomalyFunctionMetadata = new HashMap<>();
  private final AnomalyFunctionFactory anomalyFunctionFactory;
  private AnomalyFunctionManager anomalyFunctionDAO;
  private MergedAnomalyResultManager mergedAnomalyResultDAO;
  private RawAnomalyResultManager rawAnomalyResultDAO;
  private AutotuneConfigManager autotuneConfigDAO;
  private EmailConfigurationManager emailConfigurationDAO;

  private static final String DEFAULT_CRON = "0 0 0 * * ?";
  private static final String UTF8 = "UTF-8";
  private static final String DEFAULT_FUNCTION_TYPE = "WEEK_OVER_WEEK_RULE";

  public AnomalyFunctionResource(String functionConfigPath) {
    buildFunctionMetadata(functionConfigPath);
    this.anomalyFunctionDAO = DAO_REGISTRY.getAnomalyFunctionDAO();
    this.rawAnomalyResultDAO = DAO_REGISTRY.getRawAnomalyResultDAO();
    this.mergedAnomalyResultDAO = DAO_REGISTRY.getMergedAnomalyResultDAO();
    this.autotuneConfigDAO = DAO_REGISTRY.getAutotuneConfigDAO();
    this.emailConfigurationDAO = DAO_REGISTRY.getEmailConfigurationDAO();
    this.anomalyFunctionFactory = new AnomalyFunctionFactory(functionConfigPath);
  }

  /************* CRUD for anomaly functions of collection **********************************************/
  // View all anomaly functions
  @GET
  @Path("/view")
  public List<AnomalyFunctionDTO> viewAnomalyFunctions(@NotNull @QueryParam("dataset") String dataset,
      @QueryParam("metric") String metric) {

    if (StringUtils.isBlank(dataset)) {
      throw new IllegalArgumentException("dataset is a required query param");
    }

    List<AnomalyFunctionDTO> anomalyFunctionSpecs = anomalyFunctionDAO.findAllByCollection(dataset);
    List<AnomalyFunctionDTO> anomalyFunctions = anomalyFunctionSpecs;

    if (StringUtils.isNotEmpty(metric)) {
      anomalyFunctions = new ArrayList<>();
      for (AnomalyFunctionDTO anomalyFunctionSpec : anomalyFunctionSpecs) {
        if (metric.equals(anomalyFunctionSpec.getTopicMetric())) {
          anomalyFunctions.add(anomalyFunctionSpec);
        }
      }
    }
    return anomalyFunctions;
  }

  // Add anomaly function
  @POST
  @Path("/create")
  public Response createAnomalyFunction(@NotNull @QueryParam("dataset") String dataset,
      @NotNull @QueryParam("functionName") String functionName,
      @NotNull @QueryParam("metric") String metric,
      @NotNull @QueryParam("metricFunction") String metric_function,
      @QueryParam("type") String type,
      @NotNull @QueryParam("windowSize") String windowSize,
      @NotNull @QueryParam("windowUnit") String windowUnit,
      @QueryParam("windowDelay") String windowDelay,
      @QueryParam("cron") String cron,
      @QueryParam("windowDelayUnit") String windowDelayUnit,
      @QueryParam("exploreDimension") String exploreDimensions,
      @QueryParam("filters") String filters,
      @NotNull @QueryParam("properties") String properties,
      @QueryParam("isActive") boolean isActive)
      throws Exception {

    if (StringUtils.isEmpty(dataset) || StringUtils.isEmpty(functionName) || StringUtils.isEmpty(metric)
        || StringUtils.isEmpty(windowSize) || StringUtils.isEmpty(windowUnit) || StringUtils.isEmpty(properties)) {
      throw new UnsupportedOperationException("Received null for one of the mandatory params: "
          + "dataset " + dataset + ", functionName " + functionName + ", metric " + metric
          + ", windowSize " + windowSize + ", windowUnit " + windowUnit + ", properties" + properties);
    }

    DatasetConfigDTO datasetConfig = DAO_REGISTRY.getDatasetConfigDAO().findByDataset(dataset);
    TimeSpec timespec = ThirdEyeUtils.getTimeSpecFromDatasetConfig(datasetConfig);
    TimeGranularity dataGranularity = timespec.getDataGranularity();

    AnomalyFunctionDTO anomalyFunctionSpec = new AnomalyFunctionDTO();
    anomalyFunctionSpec.setActive(isActive);
    anomalyFunctionSpec.setMetricFunction(MetricAggFunction.valueOf(metric_function));
    anomalyFunctionSpec.setCollection(dataset);
    anomalyFunctionSpec.setFunctionName(functionName);
    anomalyFunctionSpec.setTopicMetric(metric);
    anomalyFunctionSpec.setMetrics(Arrays.asList(metric));
    if (StringUtils.isEmpty(type)) {
      type = DEFAULT_FUNCTION_TYPE;
    }
    anomalyFunctionSpec.setType(type);
    anomalyFunctionSpec.setWindowSize(Integer.valueOf(windowSize));
    anomalyFunctionSpec.setWindowUnit(TimeUnit.valueOf(windowUnit));

    // Setting window delay time / unit
    TimeUnit dataGranularityUnit = dataGranularity.getUnit();

    // default window delay time = 10 hours
    int windowDelayTime;
    TimeUnit windowDelayTimeUnit;
    switch (dataGranularityUnit) {
      case MINUTES:
        windowDelayTime = 30;
        windowDelayTimeUnit = TimeUnit.MINUTES;
        break;
      case DAYS:
        windowDelayTime = 0;
        windowDelayTimeUnit = TimeUnit.DAYS;
        break;
      case HOURS:
      default:
        windowDelayTime = 10;
        windowDelayTimeUnit = TimeUnit.HOURS;
    }
    anomalyFunctionSpec.setWindowDelayUnit(windowDelayTimeUnit);
    anomalyFunctionSpec.setWindowDelay(windowDelayTime);

    // bucket size and unit are defaulted to the collection granularity
    anomalyFunctionSpec.setBucketSize(dataGranularity.getSize());
    anomalyFunctionSpec.setBucketUnit(dataGranularity.getUnit());

    if(StringUtils.isNotEmpty(exploreDimensions)) {
      anomalyFunctionSpec.setExploreDimensions(getDimensions(dataset, exploreDimensions));
    }
    if (!StringUtils.isBlank(filters)) {
      filters = URLDecoder.decode(filters, UTF8);
      String filterString = ThirdEyeUtils.getSortedFiltersFromJson(filters);
      anomalyFunctionSpec.setFilters(filterString);
    }
    anomalyFunctionSpec.setProperties(properties);

    if (StringUtils.isEmpty(cron)) {
      cron = DEFAULT_CRON;
    } else {
      // validate cron
      if (!CronExpression.isValidExpression(cron)) {
        throw new IllegalArgumentException("Invalid cron expression for cron : " + cron);
      }
    }
    anomalyFunctionSpec.setCron(cron);

    Long id = anomalyFunctionDAO.save(anomalyFunctionSpec);
    return Response.ok(id).build();
  }


  // Edit anomaly function
  @POST
  @Path("/update")
  public Response updateAnomalyFunction(@NotNull @QueryParam("id") Long id,
      @NotNull @QueryParam("dataset") String dataset,
      @NotNull @QueryParam("functionName") String functionName,
      @NotNull @QueryParam("metric") String metric,
      @QueryParam("type") String type,
      @NotNull @QueryParam("windowSize") String windowSize,
      @NotNull @QueryParam("windowUnit") String windowUnit,
      @NotNull @QueryParam("windowDelay") String windowDelay,
      @QueryParam("cron") String cron,
      @QueryParam("windowDelayUnit") String windowDelayUnit,
      @QueryParam("exploreDimension") String exploreDimensions,
      @QueryParam("filters") String filters,
      @NotNull @QueryParam("properties") String properties,
      @QueryParam("isActive") boolean isActive) throws Exception {

    if (id == null || StringUtils.isEmpty(dataset) || StringUtils.isEmpty(functionName)
        || StringUtils.isEmpty(metric) || StringUtils.isEmpty(windowSize) || StringUtils.isEmpty(windowUnit)
        || StringUtils.isEmpty(windowDelay) || StringUtils.isEmpty(properties)) {
      throw new UnsupportedOperationException("Received null for one of the mandatory params: "
          + "id " + id + ",dataset " + dataset + ", functionName " + functionName + ", metric " + metric
          + ", windowSize " + windowSize + ", windowUnit " + windowUnit + ", windowDelay " + windowDelay
          + ", properties" + properties);
    }

    AnomalyFunctionDTO anomalyFunctionSpec = anomalyFunctionDAO.findById(id);
    if (anomalyFunctionSpec == null) {
      throw new IllegalStateException("AnomalyFunctionSpec with id " + id + " does not exist");
    }

    DatasetConfigDTO datasetConfig = DAO_REGISTRY.getDatasetConfigDAO().findByDataset(dataset);
    TimeSpec timespec = ThirdEyeUtils.getTimeSpecFromDatasetConfig(datasetConfig);
    TimeGranularity dataGranularity = timespec.getDataGranularity();

    anomalyFunctionSpec.setActive(isActive);
    anomalyFunctionSpec.setCollection(dataset);
    anomalyFunctionSpec.setFunctionName(functionName);
    anomalyFunctionSpec.setTopicMetric(metric);
    anomalyFunctionSpec.setMetrics(Arrays.asList(metric));
    if (StringUtils.isEmpty(type)) {
      type = DEFAULT_FUNCTION_TYPE;
    }
    anomalyFunctionSpec.setType(type);
    anomalyFunctionSpec.setWindowSize(Integer.valueOf(windowSize));
    anomalyFunctionSpec.setWindowUnit(TimeUnit.valueOf(windowUnit));

    // bucket size and unit are defaulted to the collection granularity
    anomalyFunctionSpec.setBucketSize(dataGranularity.getSize());
    anomalyFunctionSpec.setBucketUnit(dataGranularity.getUnit());

    if (!StringUtils.isBlank(filters)) {
      filters = URLDecoder.decode(filters, UTF8);
      String filterString = ThirdEyeUtils.getSortedFiltersFromJson(filters);
      anomalyFunctionSpec.setFilters(filterString);
    }
    anomalyFunctionSpec.setProperties(properties);

    if(StringUtils.isNotEmpty(exploreDimensions)) {
      // Ensure that the explore dimension names are ordered as schema dimension names
      anomalyFunctionSpec.setExploreDimensions(getDimensions(dataset, exploreDimensions));
    }
    if (StringUtils.isEmpty(cron)) {
      cron = DEFAULT_CRON;
    } else {
      // validate cron
      if (!CronExpression.isValidExpression(cron)) {
        throw new IllegalArgumentException("Invalid cron expression for cron : " + cron);
      }
    }
    anomalyFunctionSpec.setCron(cron);

    anomalyFunctionDAO.update(anomalyFunctionSpec);
    return Response.ok(id).build();
  }

  // Partially update anomaly function

  /**
   * Update the properties of the given anomaly function id
   * @param id
   *    The id of the given anomaly function
   * @param config
   *    The json string defining the function properties to be updated, ex. {"pValueThreshold":"0.01",...}
   * @return
   *    OK if the properties are successfully updated
   */
  @PUT
  @Path("/update")
  public Response updateAnomalyFunctionProperties (@QueryParam("id") @NotNull Long id,
      @QueryParam("config") @NotNull String config) {
    if(id == null || anomalyFunctionDAO.findById(id) == null) {
      String msg = "Unable to update function properties. " + id + " doesn't exist";
      LOG.warn(msg);
      return Response.status(Response.Status.BAD_REQUEST).entity(msg).build();
    }

    if(StringUtils.isNotBlank(config)) {
      Map<String, String> configs = Collections.emptyMap();
      try {
        configs = OBJECT_MAPPER.readValue(config, Map.class);
      } catch (IOException e) {
        String msg = "Unable to parse json string " + config + " for function " + id;
        LOG.error(msg);
        return Response.status(Response.Status.BAD_REQUEST).entity(msg).build();
      }
      AnomalyFunctionDTO anomalyFunction = anomalyFunctionDAO.findById(id);
      anomalyFunction.updateProperties(configs);
      anomalyFunctionDAO.update(anomalyFunction);
    }
    String msg = "Successfully update properties for function " + id + " with " + config;
    LOG.info(msg);
    return Response.ok(id).build();
  }

  // Activate anomaly function
  @POST
  @Path("/anomaly-function/activate")
  public Response activateAnomalyFunction(@NotNull @QueryParam("functionId") Long id) {
    if (id == null) {
      return Response.status(Response.Status.BAD_REQUEST).build();
    }
    toggleFunctionById(id, true);
    return Response.ok(id).build();
  }

  @DELETE
  @Path("/anomaly-function/activate")
  public Response deactivateAnomalyFunction(@NotNull @QueryParam("functionId") Long id) {
    if (id == null) {
      return Response.status(Response.Status.BAD_REQUEST).build();
    }
    toggleFunctionById(id, false);
    return Response.ok(id).build();
  }

  // batch activate and deactivate anomaly functions
  @POST
  @Path("/activate/batch")
  public String activateFunction(@QueryParam("functionIds") String functionIds) {
    toggleFunctions(functionIds, true);
    return functionIds;
  }

  @POST
  @Path("/deactivate/batch")
  public String deactivateFunction(@QueryParam("functionIds") String functionIds) {
    toggleFunctions(functionIds, false);
    return functionIds;
  }

  /**
   * toggle anomaly functions to active and inactive
   *
   * @param functionIds string comma separated function ids, ALL meaning all functions
   * @param isActive boolean true or false, set function as true or false
   */
  private void toggleFunctions(String functionIds, boolean isActive) {
    List<Long> functionIdsList = new ArrayList<>();

    // can add tokens here to activate and deactivate all functions for example
    // functionIds == {SPECIAL TOKENS} --> functionIdsList = anomalyFunctionDAO.findAll()

    if (StringUtils.isNotBlank(functionIds)) {
      String[] tokens = functionIds.split(",");
      for (String token : tokens) {
        functionIdsList.add(Long.valueOf(token));  // unhandled exception is expected
      }
    }

    for (long id : functionIdsList) {
      toggleFunctionById(id, isActive);
    }
  }


  private void toggleFunctionById(long id, boolean isActive) {
    AnomalyFunctionDTO anomalyFunctionSpec = anomalyFunctionDAO.findById(id);
    anomalyFunctionSpec.setActive(isActive);
    anomalyFunctionDAO.update(anomalyFunctionSpec);
  }

  // Delete anomaly function
  @DELETE
  @Path("/delete")
  public Response deleteAnomalyFunctions(@NotNull @QueryParam("id") Long id,
      @QueryParam("functionName") String functionName)
      throws IOException {

    if (id == null) {
      throw new IllegalArgumentException("id is a required query param");
    }

    // call endpoint to shutdown if active
    AnomalyFunctionDTO anomalyFunctionSpec = anomalyFunctionDAO.findById(id);
    if (anomalyFunctionSpec == null) {
      throw new IllegalStateException("No anomalyFunctionSpec with id " + id);
    }

    // delete dependent entities
    // email config mapping
    List<EmailConfigurationDTO> emailConfigurations = emailConfigurationDAO.findByFunctionId(id);
    for (EmailConfigurationDTO emailConfiguration : emailConfigurations) {
      emailConfiguration.getFunctions().remove(anomalyFunctionSpec);
      emailConfigurationDAO.update(emailConfiguration);
    }

    // raw result mapping
    List<RawAnomalyResultDTO> rawResults =
        rawAnomalyResultDAO.findAllByTimeAndFunctionId(0, System.currentTimeMillis(), id);
    for (RawAnomalyResultDTO result : rawResults) {
      rawAnomalyResultDAO.delete(result);
    }

    // merged anomaly mapping
    List<MergedAnomalyResultDTO> mergedResults = mergedAnomalyResultDAO.findByFunctionId(id, true);
    for (MergedAnomalyResultDTO result : mergedResults) {
      mergedAnomalyResultDAO.delete(result);
    }

    // delete from db
    anomalyFunctionDAO.deleteById(id);
    return Response.noContent().build();
  }

  /**
   * Apply an autotune configuration to an existing function
   * @param id
   * The id of an autotune configuration
   * @param isCloneFunction
   * Should we clone the function or simply apply the autotune configuration to the existing function
   * @return
   * an activated anomaly detection function
   */
  @POST
  @Path("/apply/{autotuneConfigId}")
  public Response applyAutotuneConfig(@PathParam("autotuneConfigId") @NotNull long id,
      @QueryParam("cloneFunction") @DefaultValue("false") boolean isCloneFunction,
      @QueryParam("cloneAnomalies") Boolean isCloneAnomalies) {
    Map<String, String> responseMessage = new HashMap<>();
    AutotuneConfigDTO autotuneConfigDTO = autotuneConfigDAO.findById(id);
    if (autotuneConfigDTO == null) {
      responseMessage.put("message", "Cannot find the autotune configuration entry " + id + ".");
      return Response.status(Response.Status.BAD_REQUEST).entity(responseMessage).build();
    }
    if (autotuneConfigDTO.getConfiguration() == null || autotuneConfigDTO.getConfiguration().isEmpty()) {
      responseMessage.put("message", "Autotune configuration is null or empty. The original function is optimal. Nothing to change");
      return Response.ok(responseMessage).build();
    }

    if (isCloneAnomalies == null) { // if isCloneAnomalies is not given, assign a default value
      isCloneAnomalies = containsLabeledAnomalies(autotuneConfigDTO.getFunctionId());
    }

    AnomalyFunctionDTO originalFunction = anomalyFunctionDAO.findById(autotuneConfigDTO.getFunctionId());
    AnomalyFunctionDTO targetFunction = originalFunction;

    // clone anomaly function and its anomaly results if requested
    if (isCloneFunction) {
      OnboardResource
          onboardResource = new OnboardResource(anomalyFunctionDAO, mergedAnomalyResultDAO, rawAnomalyResultDAO);
      long cloneId;
      String tag = "clone";
      try {
        cloneId = onboardResource.cloneAnomalyFunctionById(originalFunction.getId(), "clone", isCloneAnomalies);
      } catch (Exception e) {
        responseMessage.put("message", "Unable to clone function " + originalFunction.getId() + " with clone tag \"clone\"");
        LOG.warn("Unable to clone function {} with clone tag \"{}\"", originalFunction.getId(), "clone");
        return Response.status(Response.Status.CONFLICT).entity(responseMessage).build();
      }
      targetFunction = anomalyFunctionDAO.findById(cloneId);
    }

    // Verify if to update alert filter or function configuraions
    // if auto tune method is EXHAUSTIVE, which belongs to function auto tune, need to update function configurations
    // if auto tune method is ALERT_FILTER_LOGISITC_AUTO_TUNE or INITIATE_ALERT_FILTER_LOGISTIC_AUTO_TUNE, alert filter is to be updated
    if (autotuneConfigDTO.getAutotuneMethod() != EXHAUSTIVE) {
      targetFunction.setAlertFilter(autotuneConfigDTO.getConfiguration());
    } else {
      // Update function configuration
      targetFunction.updateProperties(autotuneConfigDTO.getConfiguration());
    }
    targetFunction.setActive(true);
    anomalyFunctionDAO.update(targetFunction);

    // Deactivate original function
    if (isCloneFunction) {
      originalFunction.setActive(false);
      anomalyFunctionDAO.update(originalFunction);
    }

    return Response.ok(targetFunction).build();
  }

  private void buildFunctionMetadata(String functionConfigPath) {
    Properties props = new Properties();
    InputStream input = null;
    try {
      input = new FileInputStream(functionConfigPath);
      props.load(input);
    } catch (IOException e) {
      LOG.error("Function config not found at {}", functionConfigPath);
    } finally {
      IOUtils.closeQuietly(input);
    }
    LOG.info("Loaded functions : " + props.keySet() + " from path : " + functionConfigPath);
    for (Object key : props.keySet()) {
      String functionName = key.toString();
      try {
        Class<AnomalyFunction> clz = (Class<AnomalyFunction>) Class.forName(props.get(functionName).toString());
        Method getFunctionProps = clz.getMethod("getPropertyKeys");
        AnomalyFunction anomalyFunction = clz.newInstance();
        anomalyFunctionMetadata.put(functionName, getFunctionProps.invoke(anomalyFunction));
      } catch (ClassNotFoundException e) {
        LOG.warn("Unknown class for function : " + functionName);
      } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
        LOG.error("Unknown method", e);
      } catch (InstantiationException e) {
        LOG.error("Unsupported anomaly function", e);
      }
    }
  }

  /**
   * @return map of function name vs function property keys
   * <p/>
   * eg. { "WEEK_OVER_WEEK_RULE":["baseline","changeThreshold","averageVolumeThreshold"],
   * "MIN_MAX_THRESHOLD":["min","max"] }
   */
  @GET
  @Path("/metadata")
  public Map<String, Object> getAnomalyFunctionMetadata() {
    return anomalyFunctionMetadata;
  }

  /**
   * @return List of metric functions
   * <p/>
   * eg. ["SUM","AVG","COUNT"]
   */
  @GET
  @Path("/metric-function")
  public MetricAggFunction[] getMetricFunctions() {
    return MetricAggFunction.values();
  }

  @POST
  @Path("/analyze")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response analyze(AnomalyFunctionDTO anomalyFunctionSpec,
      @QueryParam("startTime") Long startTime, @QueryParam("endTime") Long endTime)
      throws Exception {
    // TODO: replace this with Job/Task framework and job tracker page
    BaseAnomalyFunction anomalyFunction = anomalyFunctionFactory.fromSpec(anomalyFunctionSpec);

    DateTime windowStart = new DateTime(startTime);
    DateTime windowEnd = new DateTime(endTime);

    AnomalyDetectionInputContextBuilder anomalyDetectionInputContextBuilder =
        new AnomalyDetectionInputContextBuilder(anomalyFunctionFactory);
    anomalyDetectionInputContextBuilder
        .setFunction(anomalyFunctionSpec)
        .fetchTimeSeriesData(windowStart, windowEnd)
        .fetchScalingFactors(windowStart, windowEnd);
    AnomalyDetectionInputContext anomalyDetectionInputContext = anomalyDetectionInputContextBuilder.build();

    Map<DimensionMap, MetricTimeSeries> dimensionKeyMetricTimeSeriesMap =
        anomalyDetectionInputContext.getDimensionMapMetricTimeSeriesMap();

    List<RawAnomalyResultDTO> anomalyResults = new ArrayList<>();
    List<RawAnomalyResultDTO> results = new ArrayList<>();

    for (Map.Entry<DimensionMap, MetricTimeSeries> entry : dimensionKeyMetricTimeSeriesMap.entrySet()) {
      DimensionMap dimensionMap = entry.getKey();
      if (entry.getValue().getTimeWindowSet().size() < 2) {
        LOG.warn("Insufficient data for {} to run anomaly detection function", dimensionMap);
        continue;
      }
      try {
        // Run algorithm
        MetricTimeSeries metricTimeSeries = entry.getValue();
        LOG.info("Analyzing anomaly function with dimensionKey: {}, windowStart: {}, windowEnd: {}",
            dimensionMap, startTime, endTime);

        List<RawAnomalyResultDTO> resultsOfAnEntry = anomalyFunction
            .analyze(dimensionMap, metricTimeSeries, new DateTime(startTime), new DateTime(endTime),
                new ArrayList<MergedAnomalyResultDTO>());
        if (resultsOfAnEntry.size() != 0) {
          results.addAll(resultsOfAnEntry);
        }
        LOG.info("{} has {} anomalies in window {} to {}", dimensionMap, resultsOfAnEntry.size(),
            new DateTime(startTime), new DateTime(endTime));
      } catch (Exception e) {
        LOG.error("Could not compute for {}", dimensionMap, e);
      }
    }
    if (results.size() > 0) {
      List<RawAnomalyResultDTO> validResults = new ArrayList<>();
      for (RawAnomalyResultDTO anomaly : results) {
        if (!anomaly.isDataMissing()) {
          LOG.info("Found anomaly, sev [{}] start [{}] end [{}]", anomaly.getWeight(),
              new DateTime(anomaly.getStartTime()), new DateTime(anomaly.getEndTime()));
          validResults.add(anomaly);
        }
      }
      anomalyResults.addAll(validResults);
    }
    return Response.ok(anomalyResults).build();
  }

  private String getDimensions(String dataset, String exploreDimensions) throws Exception {
    // Ensure that the explore dimension names are ordered as schema dimension names
    List<String> schemaDimensionNames = CACHE_REGISTRY_INSTANCE.getDatasetConfigCache().get(dataset).getDimensions();
    Set<String> splitExploreDimensions = new HashSet<>(Arrays.asList(exploreDimensions.trim().split(",")));
    StringBuilder reorderedExploreDimensions = new StringBuilder();
    String separator = "";
    for (String dimensionName : schemaDimensionNames) {
      if (splitExploreDimensions.contains(dimensionName)) {
        reorderedExploreDimensions.append(separator).append(dimensionName);
        separator = ",";
      }
    }
    return reorderedExploreDimensions.toString();
  }

  /**
   * Check if the given function contains labeled anomalies
   * @param functionId
   * an id of an anomaly detection function
   * @return
   * true if there are labeled anomalies detected by the function
   */
  private boolean containsLabeledAnomalies(long functionId) {
    List<MergedAnomalyResultDTO> mergedAnomalies = mergedAnomalyResultDAO.findByFunctionId(functionId, true);

    for (MergedAnomalyResultDTO mergedAnomaly : mergedAnomalies) {
      AnomalyFeedback feedback = mergedAnomaly.getFeedback();
      if (feedback == null) {
        continue;
      }
      if (feedback.getFeedbackType().equals(AnomalyFeedbackType.ANOMALY) ||
          feedback.getFeedbackType().equals(AnomalyFeedbackType.ANOMALY_NEW_TREND)) {
        return true;
      }
    }
    return false;
  }
}

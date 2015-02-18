package com.linkedin.thirdeye.resource;

import com.codahale.metrics.annotation.Timed;
import com.linkedin.thirdeye.api.DimensionSpec;
import com.linkedin.thirdeye.api.StarTree;
import com.linkedin.thirdeye.api.StarTreeConstants;
import com.linkedin.thirdeye.api.StarTreeManager;
import com.linkedin.thirdeye.api.StarTreeNode;
import com.sun.jersey.api.NotFoundException;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Path("/dimensions")
@Produces(MediaType.APPLICATION_JSON)
public class DimensionsResource
{
  private final StarTreeManager starTreeManager;

  public DimensionsResource(StarTreeManager starTreeManager)
  {
    this.starTreeManager = starTreeManager;
  }

  @GET
  @Path("/{collection}")
  @Timed
  public Map<String, List<String>> getDimensions(@PathParam("collection") String collection,
                                                 @Context UriInfo uriInfo)
  {
    StarTree starTree = starTreeManager.getStarTree(collection);
    if (starTree == null)
    {
      throw new NotFoundException("No collection " + collection);
    }

    // Get fixed dimensions from query string
    Map<String, String> fixedDimensions = getFixedDimensions(starTree, uriInfo);

    // Compute all dimension values for each dimension with those values fixed
    Map<String, List<String>> allDimensionValues = new HashMap<String, List<String>>();
    for (DimensionSpec dimensionSpec : starTree.getConfig().getDimensions())
    {
      Set<String> dimensionValues = starTree.getDimensionValues(dimensionSpec.getName(), fixedDimensions);
      allDimensionValues.put(dimensionSpec.getName(), new ArrayList<String>(dimensionValues));
      Collections.sort(allDimensionValues.get(dimensionSpec.getName()));
    }

    return allDimensionValues;
  }

  @GET
  @Path("/{collection}/{nodeId}")
  @Timed
  public Map<String, List<String>> getDimensionsAtLeaf(@PathParam("collection") String collection,
                                                       @PathParam("nodeId") String nodeId,
                                                       @Context UriInfo uriInfo)
  {
    StarTree starTree = starTreeManager.getStarTree(collection);
    if (starTree == null)
    {
      throw new NotFoundException("No collection " + collection);
    }

    // Get fixed dimensions from query string
    Map<String, String> fixedDimensions = getFixedDimensions(starTree, uriInfo);

    // Find the node
    StarTreeNode leaf = getLeaf(starTree.getRoot(), UUID.fromString(nodeId));
    if (leaf == null)
    {
      throw new NotFoundException("No leaf for id " + nodeId);
    }

    // Return the dimensions at that store
    Map<String, List<String>> dimensionValues = new HashMap<String, List<String>>();
    for (DimensionSpec dimensionSpec : starTree.getConfig().getDimensions())
    {
      List<String> values = new ArrayList<String>(leaf.getRecordStore().getDimensionValues(dimensionSpec.getName()));
      dimensionValues.put(dimensionSpec.getName(), values);
    }

    return dimensionValues;
  }

  private StarTreeNode getLeaf(StarTreeNode node, UUID id)
  {
    if (node.isLeaf())
    {
      return id.equals(node.getId()) ? node : null;
    }
    else
    {
      for (StarTreeNode child : node.getChildren())
      {
        StarTreeNode leaf = getLeaf(child, id);
        if (leaf != null)
        {
          return leaf;
        }
      }

      StarTreeNode leaf = getLeaf(node.getOtherNode(), id);
      if (leaf != null)
      {
        return leaf;
      }

      leaf = getLeaf(node.getStarNode(), id);
      if (leaf != null)
      {
        return leaf;
      }

      return null;
    }
  }

  private Map<String, String> getFixedDimensions(StarTree starTree, UriInfo uriInfo)
  {
    Map<String, String> fixedDimensions = new HashMap<String, String>();
    for (DimensionSpec dimensionSpec : starTree.getConfig().getDimensions())
    {
      String dimensionValue = uriInfo.getQueryParameters().getFirst(dimensionSpec.getName());
      if (dimensionValue != null)
      {
        dimensionValue = StarTreeConstants.STAR;
      }
      fixedDimensions.put(dimensionSpec.getName(), dimensionValue);
    }
    return fixedDimensions;
  }

}

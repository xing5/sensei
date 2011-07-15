package com.sensei.search.client.servlet;


import com.browseengine.bobo.api.BrowseFacet;
import com.browseengine.bobo.api.BrowseSelection;
import com.browseengine.bobo.api.BrowseSelection.ValueOperation;
import com.browseengine.bobo.api.FacetAccessible;
import com.browseengine.bobo.api.FacetSpec;
import com.browseengine.bobo.api.FacetSpec.FacetSortSpec;
import com.browseengine.bobo.facets.DefaultFacetHandlerInitializerParam;
import com.sensei.search.req.SenseiHit;
import com.sensei.search.req.SenseiJSONQuery;
import com.sensei.search.req.SenseiQuery;
import com.sensei.search.req.SenseiRequest;
import com.sensei.search.req.SenseiResult;
import com.sensei.search.req.SenseiSystemInfo;
import com.sensei.search.util.RequestConverter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.DataConfiguration;
import org.apache.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.SortField;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import static com.sensei.search.client.servlet.SenseiSearchServletParams.*;


public class DefaultSenseiJSONServlet extends AbstractSenseiRestServlet
{

  /**
   *
   */
  private static final long serialVersionUID = 1L;

  private static Logger logger = Logger.getLogger(DefaultSenseiJSONServlet.class);

  public static JSONObject convertExpl(Explanation expl)
      throws JSONException
  {
    JSONObject jsonObject = null;
    if (expl != null)
    {
      jsonObject = new JSONObject();
      jsonObject.put(PARAM_RESULT_HITS_EXPL_VALUE, expl.getValue());
      String descr = expl.getDescription();
      jsonObject.put(PARAM_RESULT_HITS_EXPL_DESC, descr == null ? "" : descr);
      Explanation[] details = expl.getDetails();
      if (details != null)
      {
        JSONArray detailArray = new JSONArray();
        for (Explanation detail : details)
        {
          JSONObject subObj = convertExpl(detail);
          if (subObj != null)
          {
            detailArray.put(subObj);
          }
        }
        jsonObject.put(PARAM_RESULT_HITS_EXPL_DETAILS, detailArray);
      }
    }

    return jsonObject;
  }

  public static JSONObject convert(Map<String, FacetAccessible> facetValueMap, SenseiRequest req)
      throws JSONException
  {
    JSONObject resMap = new JSONObject();
    if (facetValueMap != null)
    {
      Set<Entry<String, FacetAccessible>> entrySet = facetValueMap.entrySet();

      for (Entry<String, FacetAccessible> entry : entrySet)
      {
        String fieldname = entry.getKey();

        BrowseSelection sel = req.getSelection(fieldname);
        HashSet<String> selectedVals = new HashSet<String>();
        if (sel != null)
        {
          String[] vals = sel.getValues();
          if (vals != null && vals.length > 0)
          {
            selectedVals.addAll(Arrays.asList(vals));
          }
        }

        FacetAccessible facetAccessible = entry.getValue();
        List<BrowseFacet> facetList = facetAccessible.getFacets();

        ArrayList<JSONObject> facets = new ArrayList<JSONObject>();

        for (BrowseFacet f : facetList)
        {
          String fval = f.getValue();
          if (fval != null && fval.length() > 0)
          {
            JSONObject fv = new JSONObject();
            fv.put(PARAM_RESULT_FACET_INFO_COUNT, f.getFacetValueHitCount());
            fv.put(PARAM_RESULT_FACET_INFO_VALUE, fval);
            fv.put(PARAM_RESULT_FACET_INFO_SELECTED, selectedVals.remove(fval));
            facets.add(fv);
          }
        }

        if (selectedVals.size() > 0)
        {
          // selected vals did not make it in top n
          for (String selectedVal : selectedVals)
          {
            if (selectedVal != null && selectedVal.length() > 0)
            {
              BrowseFacet selectedFacetVal = facetAccessible.getFacet(selectedVal);
              JSONObject fv = new JSONObject();
              fv.put(PARAM_RESULT_FACET_INFO_COUNT, selectedFacetVal == null ? 0 : selectedFacetVal.getFacetValueHitCount());
              String fval = selectedFacetVal == null ? selectedVal : selectedFacetVal.getValue();
              fv.put(PARAM_RESULT_FACET_INFO_VALUE, fval);
              fv.put(PARAM_RESULT_FACET_INFO_SELECTED, true);
              facets.add(fv);
            }
          }

          // we need to sort it
          FacetSpec fspec = req.getFacetSpec(fieldname);
          assert fspec != null;
          sortFacets(fieldname, facets, fspec);
        }

        resMap.put(fieldname, facets);
      }
    }
    return resMap;
  }

  private static void sortFacets(String fieldName, ArrayList<JSONObject> facets, FacetSpec fspec) {
    FacetSortSpec sortSpec = fspec.getOrderBy();
    if (FacetSortSpec.OrderHitsDesc.equals(sortSpec))
    {
      Collections.sort(facets, new Comparator<JSONObject>()
      {
        @Override
        public int compare(JSONObject o1, JSONObject o2)
        {
          try
          {
            int c1 = o1.getInt(PARAM_RESULT_FACET_INFO_COUNT);
            int c2 = o2.getInt(PARAM_RESULT_FACET_INFO_COUNT);
            int val = c2 - c1;
            if (val == 0)
            {
              String s1 = o1.getString(PARAM_RESULT_FACET_INFO_VALUE);
              String s2 = o1.getString(PARAM_RESULT_FACET_INFO_VALUE);
              val = s1.compareTo(s2);
            }
            return val;
          }
          catch (Exception e)
          {
            logger.error(e.getMessage(), e);
            return 0;
          }
        }
      });
    }
    else if (FacetSortSpec.OrderValueAsc.equals(sortSpec))
    {
      Collections.sort(facets, new Comparator<JSONObject>()
      {
        @Override
        public int compare(JSONObject o1, JSONObject o2)
        {
          try
          {
            String s1 = o1.getString(PARAM_RESULT_FACET_INFO_VALUE);
            String s2 = o1.getString(PARAM_RESULT_FACET_INFO_VALUE);
            return s1.compareTo(s2);
          }
          catch (Exception e)
          {
            logger.error(e.getMessage(), e);
            return 0;
          }
        }
      });
    }
    else
    {
      throw new IllegalStateException(fieldName + " sorting is not supported");
    }
  }

  @Override
  protected String buildResultString(SenseiRequest req, SenseiResult res)
      throws Exception
  {
    return buildJSONResultString(req, res);
  }

  public static String buildJSONResultString(SenseiRequest req, SenseiResult res)
      throws Exception
  {
    JSONObject jsonObj = buildJSONResult(req, res);
    return jsonObj.toString();
  }

  public static JSONObject buildJSONResult(SenseiRequest req, SenseiResult res)
      throws Exception
  {
    JSONObject jsonObj = new JSONObject();
    jsonObj.put(PARAM_RESULT_TID, res.getTid());
    jsonObj.put(PARAM_RESULT_TOTALDOCS, res.getTotalDocs());
    jsonObj.put(PARAM_RESULT_NUMHITS, res.getNumHits());
    jsonObj.put(PARAM_RESULT_NUMGROUPS, res.getNumGroups());
    jsonObj.put(PARAM_RESULT_PARSEDQUERY, res.getParsedQuery());

    SenseiHit[] hits = res.getSenseiHits();
    JSONArray hitArray = new JSONArray();
    jsonObj.put(PARAM_RESULT_HITS, hitArray);
    for (SenseiHit hit : hits)
    {
      Map<String, String[]> fieldMap = hit.getFieldValues();

      JSONObject hitObj = new JSONObject();
      hitObj.put(PARAM_RESULT_HIT_UID, Long.toString(hit.getUID()));
      hitObj.put(PARAM_RESULT_HIT_DOCID, Integer.toString(hit.getDocid()));
      hitObj.put(PARAM_RESULT_HIT_SCORE, Float.toString(hit.getScore()));
      hitObj.put(PARAM_RESULT_HIT_GROUPVALUE, hit.getGroupValue());
      hitObj.put(PARAM_RESULT_HIT_GROUPHITSCOUNT, hit.getGroupHitsCount());
      hitObj.put(PARAM_RESULT_HIT_SRC_DATA, hit.getSrcData());
      if (fieldMap != null)
      {
        Set<Entry<String, String[]>> entries = fieldMap.entrySet();
        for (Entry<String, String[]> entry : entries)
        {
          String key = entry.getKey();
          String[] vals = entry.getValue();

          JSONArray valArray = new JSONArray();
          if (vals != null)
          {
            for (String val : vals)
            {
              valArray.put(val);
            }
          }
          hitObj.put(key, valArray);
        }
      }

      Document doc = hit.getStoredFields();
      if (doc != null)
      {
        List<JSONObject> storedData = new ArrayList<JSONObject>();
        List<Fieldable> fields = doc.getFields();
        for (Fieldable field : fields)
        {
          JSONObject data = new JSONObject();
          data.put(PARAM_RESULT_HIT_STORED_FIELDS_NAME, field.name());
          data.put(PARAM_RESULT_HIT_STORED_FIELDS_VALUE, field.stringValue());
          storedData.add(data);
        }
        hitObj.put(PARAM_RESULT_HIT_STORED_FIELDS, new JSONArray(storedData));
      }

      Explanation expl = hit.getExplanation();
      if (expl != null)
      {
        hitObj.put(PARAM_RESULT_HIT_EXPLANATION, convertExpl(expl));
      }

      hitArray.put(hitObj);
    }

    jsonObj.put(PARAM_RESULT_TIME, res.getTime());
    jsonObj.put(PARAM_RESULT_FACETS, convert(res.getFacetMap(), req));

    return jsonObj;
  }

  private static SenseiQuery buildSenseiQuery(DataConfiguration params)
  {
    SenseiQuery sq;
    String query = params.getString(PARAM_QUERY, null);

    JSONObject qjson = new JSONObject();
    if (query != null && query.length() > 0)
    {
      try
      {
        qjson.put("query", query);
      }
      catch (Exception e)
      {
        logger.error(e.getMessage(), e);
      }
    }

    try
    {
      String[] qparams = params.getStringArray(PARAM_QUERY_PARAM);
      for (String qparam : qparams)
      {
        qparam = qparam.trim();
        if (qparam.length() == 0) continue;
        String[] parts = qparam.split(":");
        if (parts.length == 2)
        {
          qjson.put(parts[0], parts[1]);
        }
      }
    }
    catch (JSONException jse)
    {
      logger.error(jse.getMessage(), jse);
    }

    sq = new SenseiJSONQuery(qjson);
    return sq;
  }

  @Override
  protected SenseiRequest buildSenseiRequest(DataConfiguration params)
      throws Exception
  {
    return convertSenseiRequest(params);
  }

  public static SenseiRequest convertSenseiRequest(DataConfiguration params)
  {
    SenseiRequest senseiReq = new SenseiRequest();

    convertScalarParams(senseiReq, params);
    convertSenseiQuery(senseiReq, params);
    convertSortParam(senseiReq, params);
    convertSelectParam(senseiReq, params);
    convertFacetParam(senseiReq, params);
    convertInitParams(senseiReq, params);
    convertPartitionParams(senseiReq, params);

    return senseiReq;
  }

  public static void convertSenseiQuery(SenseiRequest senseiReq, DataConfiguration params) {
    senseiReq.setQuery(buildSenseiQuery(params));
  }

  public static void convertScalarParams(SenseiRequest senseiReq, DataConfiguration params) {
    senseiReq.setOffset(params.getInt(PARAM_OFFSET, 1));
    senseiReq.setCount(params.getInt(PARAM_COUNT, 10));
    senseiReq.setShowExplanation(params.getBoolean(PARAM_SHOW_EXPLAIN, false));
    senseiReq.setFetchStoredFields(params.getBoolean(PARAM_FETCH_STORED, false));
    senseiReq.setGroupBy(params.getString(PARAM_GROUP_BY, null));
    String routeParam = params.getString(PARAM_ROUTE_PARAM);
    if (routeParam != null && routeParam.length() != 0)
      senseiReq.setRouteParam(routeParam);
  }

  public static void convertPartitionParams(SenseiRequest senseiReq, DataConfiguration params)
  {
    if (params.containsKey(PARAM_PARTITIONS)) {
      List<Integer> partitions = params.getList(Integer.class, PARAM_PARTITIONS);
      senseiReq.setPartitions(new HashSet<Integer>(partitions));
    }
  }

  public static void convertInitParams(SenseiRequest senseiReq, DataConfiguration params)
  {
    Map<String, Configuration> facetParamMap = RequestConverter.parseParamConf(params, PARAM_DYNAMIC_INIT);
    Set<Entry<String, Configuration>> facetEntries = facetParamMap.entrySet();

    for (Entry<String, Configuration> facetEntry : facetEntries)
    {
      String facetName = facetEntry.getKey();
      Configuration facetConf = facetEntry.getValue();

      DefaultFacetHandlerInitializerParam facetParams = new DefaultFacetHandlerInitializerParam();

      Iterator paramsIter = facetConf.getKeys();

      while (paramsIter.hasNext())
      {
        String paramName = (String)paramsIter.next();
        Configuration paramConf = (Configuration)facetConf.getProperty(paramName);

        String type = paramConf.getString(PARAM_DYNAMIC_TYPE);
        List<String> vals = paramConf.getList(PARAM_DYNAMIC_VAL);

        try
        {
          String[] attrVals = vals.toArray(new String[0]);

          if (attrVals.length == 0 || attrVals[0].length() == 0)
          {
            logger.warn(String.format("init param has no values: facet: {0} type '{1}' ", facetName, type));
            continue;
          }

          // TODO: smarter dispatching, factory, generics
          if (type.equalsIgnoreCase(PARAM_DYNAMIC_TYPE_BOOL))
          {
            createBooleanInitParam(facetParams, paramName, attrVals);
          }
          else if (type.equalsIgnoreCase(PARAM_DYNAMIC_TYPE_STRING))
          {
            createStringInitParam(facetParams, paramName, attrVals);
          }
          else if (type.equalsIgnoreCase(PARAM_DYNAMIC_TYPE_INT))
          {
            createIntInitParam(facetParams, paramName, attrVals);
          }
          else if (type.equalsIgnoreCase(PARAM_DYNAMIC_TYPE_BYTEARRAY))
          {
            createByteArrayInitParam(facetParams, paramName, paramConf.getString(PARAM_DYNAMIC_VAL));
          }
          else if (type.equalsIgnoreCase(PARAM_DYNAMIC_TYPE_LONG))
          {
            createLongInitParam(facetParams, paramName, attrVals);
          }
          else if (type.equalsIgnoreCase(PARAM_DYNAMIC_TYPE_DOUBLE))
          {
            createDoubleInitParam(facetParams, paramName, attrVals);
          }
          else
          {
            logger.warn(String.format("Unknown init param name '{0}' type '{1}' for facet: {2} ", paramName, type, facetName));
            continue;
          }

        }
        catch (Exception e)
        {
          logger.warn(String.format("Failed to parse init param name '{0}' type '{1}' for facetName", paramName, type, facetName));
        }
      }

      senseiReq.setFacetHandlerInitializerParam(facetName, facetParams);
    }
  }

  private static void createBooleanInitParam(
      DefaultFacetHandlerInitializerParam facetParams,
      String name,
      String[] paramVals)
  {
    boolean[] vals = new boolean[paramVals.length];
    int i = 0;
    for (String paramVal : paramVals ) {
      vals[i] = Boolean.parseBoolean(paramVal);
    }

    facetParams.putBooleanParam(name, vals);
  }

  private static void createStringInitParam(
      DefaultFacetHandlerInitializerParam facetParams,
      String name,
      String[] paramVals)
  {
    facetParams.putStringParam(name, Arrays.asList(paramVals));
  }

  private static void createIntInitParam(
      DefaultFacetHandlerInitializerParam facetParams,
      String name,
      String[] paramVals)
  {
    int[] vals = new int[paramVals.length];
    int i = 0;
    for (String paramVal : paramVals ) {
      vals[i] = Integer.parseInt(paramVal);
    }

    facetParams.putIntParam(name, vals);
  }

  private static void createByteArrayInitParam(
      DefaultFacetHandlerInitializerParam facetParams,
      String name,
      String paramVal)
      throws UnsupportedEncodingException
  {
    byte[] val = paramVal.getBytes("UTF-8");
    facetParams.putByteArrayParam(name, val);
  }

  private static void createLongInitParam(
      DefaultFacetHandlerInitializerParam facetParams,
      String name,
      String[] paramVals)
  {
    long[] vals = new long[paramVals.length];
    int i = 0;
    for (String paramVal : paramVals ) {
      vals[i] = Long.parseLong(paramVal);
    }

    facetParams.putLongParam(name, vals);
  }

  private static void createDoubleInitParam(
      DefaultFacetHandlerInitializerParam facetParams,
      String name,
      String[] paramVals)
  {
    double[] vals = new double[paramVals.length];
    int i = 0;
    for (String paramVal : paramVals ) {
      vals[i] = Double.parseDouble(paramVal);
    }

    facetParams.putDoubleParam(name, vals);
  }

  public static void convertSortParam(SenseiRequest senseiReq, DataConfiguration params)
  {
    String[] sortStrings = params.getStringArray(PARAM_SORT);

    if (sortStrings != null && sortStrings.length > 0)
    {
      ArrayList<SortField> sortFieldList = new ArrayList<SortField>(sortStrings.length);

      for (String sortString : sortStrings)
      {
        sortString = sortString.trim();
        if (sortString.length() == 0) continue;
        SortField sf;
        String[] parts = sortString.split(":");
        if (parts.length == 2)
        {
          boolean reverse = PARAM_SORT_DESC.equals(parts[1]);
          sf = new SortField(parts[0], SortField.CUSTOM, reverse);
        }
        else if (parts.length == 1)
        {
          if (PARAM_SORT_SCORE.equals(parts[0]))
          {
            sf = SenseiRequest.FIELD_SCORE;
          }
          else if (PARAM_SORT_SCORE_REVERSE.equals(parts[0]))
          {
            sf = SenseiRequest.FIELD_SCORE_REVERSE;
          }
          else if (PARAM_SORT_DOC.equals(parts[0]))
          {
            sf = SenseiRequest.FIELD_DOC;
          }
          else if (PARAM_SORT_DOC_REVERSE.equals(parts[0]))
          {
            sf = SenseiRequest.FIELD_DOC_REVERSE;
          }
          else
          {
            sf = new SortField(parts[0], SortField.CUSTOM, false);
          }
        }
        else
        {
          throw new IllegalArgumentException("invalid sort string: " + sortString);
        }

        if (sf.getType() != SortField.DOC && sf.getType() != SortField.SCORE &&
            (sf.getField() == null || sf.getField().isEmpty()))   // Empty field name.
          continue;

        sortFieldList.add(sf);
      }

      senseiReq.setSort(sortFieldList.toArray(new SortField[sortFieldList.size()]));
    }
  }

  public static void convertFacetParam(SenseiRequest senseiReq, DataConfiguration params)
  {
    Map<String, Configuration> facetParamMap = RequestConverter.parseParamConf(params, PARAM_FACET);
    Set<Entry<String, Configuration>> entries = facetParamMap.entrySet();

    for (Entry<String, Configuration> entry : entries)
    {
      String name = entry.getKey();
      Configuration conf = entry.getValue();
      FacetSpec fspec = new FacetSpec();

      fspec.setExpandSelection(conf.getBoolean(PARAM_FACET_EXPAND, false));
      fspec.setMaxCount(conf.getInt(PARAM_FACET_MAX, 10));
      fspec.setMinHitCount(conf.getInt(PARAM_FACET_MINHIT, 1));

      FacetSpec.FacetSortSpec orderBy;
      String orderString = conf.getString(PARAM_FACET_ORDER, PARAM_FACET_ORDER_HITS);
      if (PARAM_FACET_ORDER_HITS.equals(orderString))
      {
        orderBy = FacetSpec.FacetSortSpec.OrderHitsDesc;
      }
      else if (PARAM_FACET_ORDER_VAL.equals(orderString))
      {
        orderBy = FacetSpec.FacetSortSpec.OrderValueAsc;
      }
      else
      {
        throw new IllegalArgumentException("invalid order string: " + orderString);
      }
      fspec.setOrderBy(orderBy);
      senseiReq.setFacetSpec(name, fspec);
    }
  }

  public static void convertSelectParam(SenseiRequest senseiReq, DataConfiguration params)
  {
    Map<String, Configuration> selectParamMap = RequestConverter.parseParamConf(params, PARAM_SELECT);
    Set<Entry<String, Configuration>> entries = selectParamMap.entrySet();

    for (Entry<String, Configuration> entry : entries)
    {
      String name = entry.getKey();
      Configuration conf = entry.getValue();

      BrowseSelection sel = new BrowseSelection(name);

      String[] vals = conf.getStringArray(PARAM_SELECT_VAL);
      for (String val : vals)
      {
        if (val.trim().length() > 0)
        {
          sel.addValue(val);
        }
      }

      vals = conf.getStringArray(PARAM_SELECT_NOT);
      for (String val : vals)
      {
        if (val.trim().length() > 0)
        {
          sel.addNotValue(val);
        }
      }

      String op = conf.getString(PARAM_SELECT_OP, PARAM_SELECT_OP_OR);

      ValueOperation valOp;
      if (PARAM_SELECT_OP_OR.equals(op))
      {
        valOp = ValueOperation.ValueOperationOr;
      }
      else if (PARAM_SELECT_OP_AND.equals(op))
      {
        valOp = ValueOperation.ValueOperationAnd;
      }
      else
      {
        throw new IllegalArgumentException("invalid selection operation: " + op);
      }
      sel.setSelectionOperation(valOp);

      String[] selectPropStrings = conf.getStringArray(PARAM_SELECT_PROP);
      if (selectPropStrings != null && selectPropStrings.length > 0)
      {
        Map<String, String> prop = new HashMap<String, String>();
        sel.setSelectionProperties(prop);
        for (String selProp : selectPropStrings)
        {
          if (selProp.trim().length() == 0) continue;

          String[] parts = selProp.split(":");
          if (parts.length == 2)
          {
            prop.put(parts[0], parts[1]);
          }
          else
          {
            throw new IllegalArgumentException("invalid prop string: " + selProp);
          }
        }
      }

      senseiReq.addSelection(sel);
    }
  }

  @Override
  protected String buildResultString(SenseiSystemInfo info) throws Exception {
    JSONObject jsonObj = new JSONObject();
    jsonObj.put(PARAM_SYSINFO_NUMDOCS, info.getNumDocs());
    jsonObj.put(PARAM_SYSINFO_LASTMODIFIED, info.getLastModified());
    jsonObj.put(PARAM_SYSINFO_VERSION, info.getVersion());

    JSONArray jsonArray = new JSONArray();
    jsonObj.put(PARAM_SYSINFO_FACETS, jsonArray);
    Set<SenseiSystemInfo.SenseiFacetInfo> facets = info.getFacetInfos();
    if (facets != null) {
        for (SenseiSystemInfo.SenseiFacetInfo facet : facets) {
          JSONObject facetObj = new JSONObject();
          facetObj.put(PARAM_SYSINFO_FACETS_NAME, facet.getName());
          facetObj.put(PARAM_SYSINFO_FACETS_RUNTIME, facet.isRunTime());
          facetObj.put(PARAM_SYSINFO_FACETS_PROPS, facet.getProps());
          jsonArray.put(facetObj);
        }
    }

    jsonArray = new JSONArray();
    jsonObj.put(PARAM_SYSINFO_CLUSTERINFO, jsonArray);
    List<SenseiSystemInfo.SenseiNodeInfo> clusterInfo = info.getClusterInfo();
    if (clusterInfo != null)
    {
      for (SenseiSystemInfo.SenseiNodeInfo nodeInfo : clusterInfo)
      {
        JSONObject nodeObj = new JSONObject();
        nodeObj.put(PARAM_SYSINFO_CLUSTERINFO_ID, nodeInfo.getId());
        nodeObj.put(PARAM_SYSINFO_CLUSTERINFO_PARTITIONS, new JSONArray(nodeInfo.getPartitions()));
        nodeObj.put(PARAM_SYSINFO_CLUSTERINFO_NODELINK, nodeInfo.getNodeLink());
        nodeObj.put(PARAM_SYSINFO_CLUSTERINFO_ADMINLINK, nodeInfo.getAdminLink());
        jsonArray.put(nodeObj);
      }
    }

    return jsonObj.toString();
  }
}
package com.neotys.dynatrace.monitoring;

import com.google.common.base.Optional;
import com.neotys.dynatrace.common.DynatraceException;
import com.neotys.dynatrace.common.HTTPGenerator;
import com.neotys.extensions.action.engine.Context;
import com.neotys.extensions.action.engine.Proxy;
import com.neotys.rest.dataexchange.client.DataExchangeAPIClient;
import com.neotys.rest.dataexchange.client.DataExchangeAPIClientFactory;
import com.neotys.rest.dataexchange.model.ContextBuilder;
import com.neotys.rest.dataexchange.model.EntryBuilder;
import com.neotys.rest.error.NeotysAPIException;
import org.apache.http.client.ClientProtocolException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.*;
import java.util.Map.Entry;

import static com.neotys.dynatrace.common.HTTPGenerator.HTTP_GET_METHOD;
import static com.neotys.dynatrace.common.HTTPGenerator.HTTP_POST_METHOD;

public class DynatraceIntegration {
	private static final String DYNATRACE_URL = ".live.dynatrace.com/api/v1/";
	private static final String DYNATRACE_APPLICATION = "entity/services";
	private static final String DYNATRACE_API_PROCESS_GROUP = "entity/infrastructure/process-groups";
	private static final String DYNATRACE_HOSTS = "entity/infrastructure/hosts";
	private static final String DYNATRACE_TIMESERIES = "timeseries";
	private static final String DYNATRACE_PROTOCOL = "https://";
	private static final String NEOLOAD_LOCATION = "Dynatrace";

	private static final String DIMENSION_PROCESS_INSTANCE = "PROCESS_GROUP_INSTANCE";
	private static final String DIMENSION_PROCESS_GROUP = "PROCESS_GROUP";
	private static final String DIMENSION_HOST = "HOST";

	private final Optional<String> proxyName;
	private DataExchangeAPIClient dataExchangeApiClient;
	private ContextBuilder contextBuilder;
	private HTTPGenerator httpGenerator;
	private EntryBuilder entryBuilder;
	private String dynatraceApiKey;
	private String dynatraceId;
	private String dynatraceApplication;
	private List<String> dynatraceApplicationServiceIds;
	private List<String> dynatraceApplicationHostIds;
	private String dynatraceApplicationName = "";
	private Map<String, String> header = null;
	private Map<String, String> parameters = null;
	private static List<String> relevantDimensions = Arrays.asList(DIMENSION_PROCESS_GROUP, DIMENSION_HOST);
	private static List<String> aggregateType = Arrays.asList("AVG", "COUNT");
	private String dynatraceManagedHostname = null;
	private static Map<String, String> timeseriesInfra;
	private static Map<String, String> timeseriesServices;
	private boolean isRunning = true;
	private final Context context;
	private long startTS;

	public DynatraceIntegration(final Context context,
								final String dynatraceApiKey,
								final String dynatraceId,
								final Optional<String> dynatraceTags,
								final String dataExchangeApiUrl,
								final Optional<String> dataExchangeApiKey,
								final Optional<String> proxyName,
								final Optional<String> dynatraceManagedHostname,
								final long startTs) throws Exception {
		this.context = context;
		this.startTS = startTs;
		this.contextBuilder = new ContextBuilder().hardware("Dynatrace").location(NEOLOAD_LOCATION).software("OneAgent")
				.script("DynatraceMonitoring" + System.currentTimeMillis());
		this.dynatraceApiKey = dynatraceApiKey;
		this.dynatraceApplication = dynatraceTags.orNull();
		this.dynatraceId = dynatraceId;
		this.dynatraceManagedHostname = dynatraceManagedHostname.orNull();
		this.isRunning = true;
		this.proxyName = proxyName;
		initTimeseriesHashMap();
		this.dataExchangeApiClient = DataExchangeAPIClientFactory.newClient(dataExchangeApiUrl, contextBuilder.build(), dataExchangeApiKey.orNull());
		initHttpClient();
		this.dynatraceApplicationServiceIds = getApplicationId();
		getHostsFromProcessGroup();
		getHosts();
		getDynatraceData();
	}

	private boolean isRelevantDimension(final JSONArray array) {
		for (String listItem : relevantDimensions) {
			for (int i = 0; i < array.length(); i++) {
				if (array.getString(i).contains(listItem)) {
					return true;
				}
			}
		}
		return false;
	}

	public String getTags(String applicationName) {
		String result = null;
		String[] tagsTable = null;
		if (applicationName != null) {
			if (applicationName.contains(",")) {
				tagsTable = applicationName.split(",");
				result = "";
				for (String tag : tagsTable) {
					result += tag + "AND";
				}
				result = result.substring(0, result.length() - 3);
			} else {
				result = applicationName;
			}
		}
		return result;

	}

	// TODO why is it not used?
	private void configureHttpsfordynatrace() throws NoSuchAlgorithmException, KeyManagementException {
		httpGenerator.setAllowHostnameSSL();
	}

	private void createEntry(final String entityName, final String metricName,
							 final String metricValueName, final double value,
							 final String unit, final long valueDate)
			throws GeneralSecurityException, IOException, URISyntaxException, NeotysAPIException {
		entryBuilder = new EntryBuilder(Arrays.asList("Dynatrace", entityName, metricName, metricValueName), valueDate);
		entryBuilder.unit(unit);
		entryBuilder.value(value);
		dataExchangeApiClient.addEntry(entryBuilder.build());
	}

	private Optional<Proxy> getProxy(final Optional<String> proxyName, final String url) throws MalformedURLException {
		if (proxyName.isPresent()) {
			return Optional.fromNullable(context.getProxyByName(proxyName.get(), new URL(url)));
		}
		return Optional.absent();
	}

	private List<String> getApplicationId() throws DynatraceException, IOException, URISyntaxException {
		JSONArray jsonObj;
		String url;
		JSONObject jsonApplication;
		String tags = getTags(dynatraceApplication);

		url = getApiUrl() + DYNATRACE_APPLICATION;
		parameters = new HashMap<>();
		parameters.put("tag", tags);
		sendTokenIngetParam(parameters);
		//initHttpClient();
		final Optional<Proxy> proxy = getProxy(proxyName, url);
		httpGenerator = new HTTPGenerator(HTTP_GET_METHOD, url, header, parameters, proxy);

		jsonObj = httpGenerator.executeAndGetJsonArrayResponse();
		if (jsonObj != null) {
			dynatraceApplicationServiceIds = new ArrayList<>();
			for (int i = 0; i < jsonObj.length(); i++) {
				jsonApplication = jsonObj.getJSONObject(i);
				if (jsonApplication.has("entityId")) {
					dynatraceApplicationServiceIds.add(jsonApplication.getString("entityId"));
				}
			}
		} else {
			dynatraceApplicationServiceIds = null;
		}

		if (dynatraceApplicationServiceIds == null) {
			throw new DynatraceException("No Application find in The Dynatrace Account with the name " + dynatraceApplicationName);
		}

		httpGenerator.closeHttpClient();

		return dynatraceApplicationServiceIds;

	}

	private HashMap<String, String> getEntityDefinition(final JSONObject entity) {
		final HashMap<String, String>result = new HashMap<>();
		for (Object key : entity.keySet()) {
			result.put((String) key, (String) entity.get((String) key));
		}
		return result;

	}

	private String getEntityDisplayName(final Map<String, String> map, final String entity) {
		final String[] entities = entity.split(",");
		for (Entry<String, String> e : map.entrySet()) {
			for (String entityFromMap : entities) {
				if (entityFromMap.equalsIgnoreCase(e.getKey())) {
					return e.getValue();
				}
			}
		}
		return null;
	}

	private void getHosts() throws IOException, URISyntaxException {
		JSONArray jsonArray;
		JSONObject jsonApplication;

		String tags = getTags(dynatraceApplication);
		String url = getApiUrl() + DYNATRACE_HOSTS;
		final Map<String, String> parameters = new HashMap<>();
		parameters.put("tag", tags);
		sendTokenIngetParam(parameters);

		final Optional<Proxy> proxy = getProxy(proxyName, url);
		httpGenerator = new HTTPGenerator(HTTP_GET_METHOD, url, header, parameters, proxy);

		jsonArray = httpGenerator.executeAndGetJsonArrayResponse();
		if (jsonArray != null) {
			for (int i = 0; i < jsonArray.length(); i++) {
				jsonApplication = jsonArray.getJSONObject(i);
				if (jsonApplication.has("entityId")) {
					if (jsonApplication.has("displayName")) {
						dynatraceApplicationHostIds.add(jsonApplication.getString("entityId"));
					}
				}
			}

		}

		httpGenerator.closeHttpClient();
	}

	private void getHostsFromProcessGroup() throws IOException, NoSuchAlgorithmException, URISyntaxException {
		JSONArray jsonObj;
		JSONObject jsonApplication;
		JSONObject jsonFromRelation;
		JSONArray jsonRunOn;
		String tags = getTags(dynatraceApplication);
		String url = getApiUrl() + DYNATRACE_API_PROCESS_GROUP;
		final Map<String, String> parameters = new HashMap<>();
		parameters.put("tag", tags);
		sendTokenIngetParam(parameters);

		final Optional<Proxy> proxy = getProxy(proxyName, url);
		httpGenerator = new HTTPGenerator(HTTP_GET_METHOD, url, header, parameters, proxy);

		jsonObj = httpGenerator.executeAndGetJsonArrayResponse();
		if (jsonObj != null) {
			dynatraceApplicationHostIds = new ArrayList<>();
			for (int i = 0; i < jsonObj.length(); i++) {
				jsonApplication = jsonObj.getJSONObject(i);
				if (jsonApplication.has("entityId")) {
					if (jsonApplication.has("fromRelationships")) {
						jsonFromRelation = jsonApplication.getJSONObject("fromRelationships");
						if (jsonFromRelation.has("runsOn")) {
							jsonRunOn = jsonFromRelation.getJSONArray("runsOn");
							if (jsonRunOn != null) {
								for (int j = 0; j < jsonRunOn.length(); j++) {
									dynatraceApplicationHostIds.add(jsonRunOn.getString(j));
								}
							}

						}
					}
				}
			}

		}

		httpGenerator.closeHttpClient();
	}

	public void sendTokenIngetParam(final Map<String, String> param) {
		param.put("Api-Token", dynatraceApiKey);
	}

	public void getDynatraceData() throws IOException, GeneralSecurityException, URISyntaxException, NeotysAPIException, ParseException {
		if (isRunning) {
			///---Send the service data of this entity-----
			for (Entry<String, String> m : timeseriesServices.entrySet()) {
				if (isRunning) {
					final List<DynatraceMetric> data = getTimeSeriesMetricData(m.getKey(), m.getKey(), m.getValue(), dynatraceApplicationServiceIds);
					sendDynatraceMetricEntity(data);
				}
			}
			//---------------------------------

			//----send the infrastructure entity---------------
			for (Entry<String, String> m : timeseriesInfra.entrySet()) {
				if (isRunning) {
					final List<DynatraceMetric> data = getTimeSeriesMetricData(m.getKey(), m.getKey(), m.getValue(), dynatraceApplicationHostIds);
					sendDynatraceMetricEntity(data);
				}
			}
		}
	}

	private void initTimeseriesHashMap() {
		///------requesting only infrastructure and services metrics-------------//
		timeseriesInfra = new HashMap<>();
		timeseriesInfra.put("com.dynatrace.builtin:host.availability", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.cpu.idle", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.cpu.iowait", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.cpu.steal", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.cpu.system", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.cpu.user", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.disk.availablespace", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.disk.bytesread", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.disk.byteswritten", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.disk.freespacepercentage", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.disk.queuelength", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.disk.readoperations", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.disk.readtime", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.disk.usedspace", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.disk.writeoperations", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.disk.writetime", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.mem.available", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.mem.availablepercentage", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.mem.pagefaults", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.mem.used", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.nic.bytesreceived", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.nic.bytessent", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:host.nic.packetsreceived", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:pgi.cpu.usage", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:pgi.jvm.committedmemory", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:pgi.jvm.garbagecollectioncount", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:pgi.jvm.garbagecollectiontime", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:pgi.jvm.threadcount", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:pgi.jvm.usedmemory", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:pgi.mem.usage", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:pgi.nic.bytesreceived", "AVG");
		timeseriesInfra.put("com.dynatrace.builtin:pgi.nic.bytessent", "AVG");
		timeseriesServices = new HashMap<>();
		timeseriesServices.put("com.dynatrace.builtin:service.clientsidefailurerate", "AVG");
		timeseriesServices.put("com.dynatrace.builtin:service.errorcounthttp4xx", "COUNT");
		timeseriesServices.put("com.dynatrace.builtin:service.errorcounthttp5xx", "COUNT");
		timeseriesServices.put("com.dynatrace.builtin:service.failurerate", "AVG");
		timeseriesServices.put("com.dynatrace.builtin:service.requestspermin", "COUNT");
		timeseriesServices.put("com.dynatrace.builtin:service.responsetime", "AVG");
		timeseriesServices.put("com.dynatrace.builtin:service.serversidefailurerate", "AVG");

	}

	private void sendDynatraceMetricEntity(final List<DynatraceMetric> metric)
			throws GeneralSecurityException, IOException, URISyntaxException, NeotysAPIException {
		for (DynatraceMetric data : metric) {
			String timeseries = data.getTimeseries();
			String[] metricname = timeseries.split(":");
			createEntry(data.getMetricName(), metricname[0], metricname[1], data.getValue(), data.getUnit(), data.getTime());
		}
	}

	public void setTestToStop() {
		isRunning = false;
	}

	public void setTestRunning() {
		isRunning = true;
	}

	private long getUtcDate() {
		long timeInMillisSinceEpoch123 = System.currentTimeMillis();
		timeInMillisSinceEpoch123 -= 120000;
		return timeInMillisSinceEpoch123;
	}

	private List<DynatraceMetric> getTimeSeriesMetricData(final String timeSeries, final String entityId,
														  final String aggregate, final List<String> listEntityId)
			throws IOException, NoSuchAlgorithmException, URISyntaxException {

		List<DynatraceMetric> metrics = new ArrayList<>();
		DynatraceMetric metric;
		JSONArray jsonObj;
		JSONObject jsonApplication;
		JSONObject jsonDataPoint;
		String entity;
		JSONObject jsonEntity;
		String url = getApiUrl() + DYNATRACE_TIMESERIES;
		Map<String, String> parameters = new HashMap<>();
		sendTokenIngetParam(parameters);
		String displayName = null;


		String jsonEntities;

		jsonEntities = "{"
				+ "\"aggregationType\": \"" + aggregate.toLowerCase() + "\","
				+ "\"timeseriesId\" : \"" + timeSeries + "\","
				+ "\"endTimestamp\":\"" + String.valueOf(System.currentTimeMillis()) + "\","
				+ "\"startTimestamp\":\"" + String.valueOf(getUtcDate()) + "\","
				+ "\"entities\":[";

		for (String entit : listEntityId) {
			jsonEntities += "\"" + entit + "\",";

		}

		jsonEntities = jsonEntities.substring(0, jsonEntities.length() - 1);
		jsonEntities += "]}";

		final Optional<Proxy> proxy = getProxy(proxyName, url);
		httpGenerator = HTTPGenerator.newJsonHttpGenerator(HTTP_POST_METHOD, url, header, parameters, proxy, jsonEntities);

		jsonApplication = httpGenerator.executeAnGetJsonResponse();
		if (jsonApplication != null) {
			if (jsonApplication.has("result")) {
				jsonApplication = jsonApplication.getJSONObject("result");
				if (jsonApplication.has("dataPoints")) {
					if (jsonApplication.has("entities")) {
						jsonEntity = jsonApplication.getJSONObject("entities");
						final Map<String, String> entities  = getEntityDefinition(jsonEntity);

						jsonDataPoint = jsonApplication.getJSONObject("dataPoints");
						Iterator<?> keys1 = jsonDataPoint.keys();
						while (keys1.hasNext()) {
							entity = (String) keys1.next();
							displayName = getEntityDisplayName(entities, entity);
							JSONArray arr = jsonDataPoint.getJSONArray(entity);

							for (int i = 0; i < arr.length(); i++) {
								JSONArray data = arr.getJSONArray(i);

								if (data.get(1) instanceof Double) {
									if (data.getLong(0) >= startTS) {
										metric = new DynatraceMetric(jsonApplication.getString("unit"), data.getDouble(1), data.getLong(0), displayName, jsonApplication.getString("timeseriesId"), entity);
										metrics.add(metric);
									}
								}

							}

						}
					}
				}
			}
		}
		httpGenerator.closeHttpClient();
		return metrics;
	}

	private HashMap<String, String> getTimeSeriesMetric(String EntityId, List<String> listEntity) throws ClientProtocolException, IOException, NoSuchAlgorithmException, URISyntaxException {
		HashMap<String, String> metrics = new HashMap<String, String>();
		JSONArray jsonObj;
		JSONObject jsonApplication;
		String jsonEntities;
		HashMap<String, String> hosts = new HashMap<String, String>();
		String url = getApiUrl() + DYNATRACE_TIMESERIES;
		HashMap<String, String> parameters = new HashMap<String, String>();
		parameters.put("aggregationType", "AVG");
		parameters.put("entity", EntityId);
		parameters.put("startTimestamp", String.valueOf(getUtcDate()));
		sendTokenIngetParam(parameters);

		jsonEntities = "{entities:[";
		for (String entit : listEntity) {
			jsonEntities += entit + ",";

		}
		jsonEntities = jsonEntities.substring(0, jsonEntities.length() - 1);
		jsonEntities += "]}";


		final Optional<Proxy> proxy = getProxy(proxyName, url);
		httpGenerator = HTTPGenerator.newJsonHttpGenerator(HTTP_POST_METHOD, url, header, parameters, proxy, jsonEntities);

		jsonObj = httpGenerator.executeAndGetJsonArrayResponse();
		if (jsonObj != null) {

			for (int i = 0; i < jsonObj.length(); i++) {
				jsonApplication = jsonObj.getJSONObject(i);
				if (jsonApplication.has("timeseriesId")) {
					if (jsonApplication.has("dimensions")) {

						if (isRelevantDimension(jsonApplication.getJSONArray("dimensions"))) {
							String aggregate = getAggregate(jsonApplication.getJSONArray("aggregationTypes"));
							if (aggregate != null)
								metrics.put(jsonApplication.getString("timeseriesId"), aggregate);
						}
					}
				}
			}

		}
		httpGenerator.closeHttpClient();
		return metrics;

	}

	private String getAggregate(final JSONArray arr) {
		String result = null;
		for (int i = 0; i < arr.length(); i++) {
			for (String entity : aggregateType) {
				if (entity.equalsIgnoreCase(arr.getString(i)))
					return entity;
			}
		}
		return result;
	}

	private String getApiUrl() {
		String result;

		if (dynatraceManagedHostname != null) {
			result = DYNATRACE_PROTOCOL + dynatraceManagedHostname + "/api/v1/";
		} else {
			result = DYNATRACE_PROTOCOL + dynatraceId + DYNATRACE_URL;
		}
		return result;
	}

	private void initHttpClient() {
		header = new HashMap<>();

		//	header.put("Authorization", "Api‐Token "+dynatraceApiKey);
		//header.put("Content-Type", "application/json");
	}
}

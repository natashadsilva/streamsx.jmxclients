// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package streams.metric.exporter.streamstracker.job;

import java.io.IOException;

import java.lang.reflect.UndeclaredThrowableException;
import java.math.BigInteger;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.management.AttributeChangeNotification;
import javax.management.MBeanServerConnection;
import javax.management.Notification;
import javax.management.NotificationFilterSupport;
import javax.management.NotificationListener;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.apache.commons.lang.time.StopWatch;

import com.ibm.streams.management.Notifications;
import com.ibm.streams.management.ObjectNameBuilder;
import com.ibm.streams.management.job.JobMXBean;
import com.ibm.streams.management.job.OperatorMXBean;
import com.ibm.streams.management.job.OperatorInputPortMXBean;
import com.ibm.streams.management.job.OperatorOutputPortMXBean;

import streams.metric.exporter.ServiceConfig;
import streams.metric.exporter.error.StreamsTrackerErrorCode;
import streams.metric.exporter.error.StreamsTrackerException;
import streams.metric.exporter.jmx.MXBeanSource;
import streams.metric.exporter.metrics.MetricsExporter;
import streams.metric.exporter.metrics.MetricsExporter.StreamsObjectType;
import streams.metric.exporter.prometheus.PrometheusMetricsExporter;
import streams.metric.exporter.streamstracker.StreamsDomainTracker;
import streams.metric.exporter.streamstracker.instance.StreamsInstanceTracker;

/* Job Details including map of port names so metrics can have names for ports rather than just ids */
public class JobDetails {
	private static final Logger LOGGER = LoggerFactory.getLogger("root." + StreamsDomainTracker.class.getName());

	private StreamsInstanceTracker monitor;
	private ServiceConfig config = null;
	private String domain = null;
	private String streamsInstanceName;
	private String instance = null;
	private String jobid = null;
	private String status = null;
	private String health = null;
	private String jobname = null;

	private String jobSnapshot = null;
	private String jobMetrics = null;

	private final Map<String, Map<String, String>> peInfoMap = new HashMap<String, Map<String, String>>();
	private final Map<String, String> operatorKindMap = new HashMap<String, String>();
	// port maps <operatorname, map<indexWithinOperator,portname>>
	private final Map<String, Map<String, String>> operatorInputPortNames = new HashMap<String, Map<String, String>>();
	private final Map<String, Map<String, String>> operatorOutputPortNames = new HashMap<String, Map<String, String>>();

		/* Metrics Exporter*/
	/* Temporary solution: always use Prometheus exporter */
	/* Future: Make this pluggable, add Elasticsearch exporter */
	private MetricsExporter metricsExporter = PrometheusMetricsExporter.getInstance();

/*
	private JobMXBean jobBean;
	//private JobMXBean.Status status;
	// private String jobResolvedMetrics = null;
	private Date lastMetricsRefresh = null;
	private Date lastMetricsFailure = null;
	private boolean lastMetricsRefreshFailed = false;
	
	private Date lastSnapshotRefresh = null;
	private Date lastSnapshotFailure = null;
	private boolean lastSnapshotRefreshFailed = false;

	private String adlFile = null;
	private String applicationName = null;
	private String applicationPath = null;
	private String applicationScope = null;
	private String applicationVersion = null;
	private String dataPath = null;
	//private JobMXBean.Health health = JobMXBean.Health.UNKNOWN;
	private String jobGroup = null;
	private String outputPath = null;
	private String startedByUser = null;
	private long submitTime = 0;
*/

	// Control over complete refresh of job required before next refresh
	//private boolean jobTopologyRefreshRequired = false;



	
	public JobDetails(StreamsInstanceTracker monitor, String jobid) {
		this.monitor = monitor;
		this.config = monitor.getConfig();

		this.domain = monitor.getDomainName();
		this.streamsInstanceName = monitor.getInstanceInfo().getInstanceName();

		setJobid(jobid);
		//setStatus(JobMXBean.Status.UNKNOWN);
		setJobMetrics(null);
		setJobSnapshot(null);

		createExportedMetrics();
	}
	
	// Called by Instance to pass in snapshot and metrics to update exported metrics
	public void refresh(String jobSnapshot, String jobMetrics) {
		LOGGER.debug("Job refresh");

		// Remove old metrics in case things moved around and new labels for things like resource are required
		this.removeExportedMetrics();
		this.createExportedMetrics();

		setJobSnapshot(jobSnapshot);
		setJobMetrics(jobMetrics);

		this.processSnapshot(jobSnapshot);
		this.processMetrics(jobMetrics);
	}

	// Create Mappings for Metric Lookup and Snapshot based metrics
	private void processSnapshot(String jobSnapshot) {
		LOGGER.debug("processSnapshot");

		// clear maps
		peInfoMap.clear();
		operatorKindMap.clear();
		operatorInputPortNames.clear();
		operatorOutputPortNames.clear();

		if (jobSnapshot != null) {

			JSONParser parser = new JSONParser();
			try {
				JSONObject snapshotObject = (JSONObject) parser.parse(jobSnapshot);

				String instance = (String)snapshotObject.get("instance");
				String status = (String)snapshotObject.get("status");
				String health = (String)snapshotObject.get("health");
				String jobname = (String)snapshotObject.get("name");

				this.instance = instance;
				this.status = status;
				this.health = health;
				this.jobname = jobname;

				LOGGER.debug("snapshot Metrics job health: " + health);

				metricsExporter.getStreamsMetric("healthy", StreamsObjectType.JOB, this.domain, instance, jobname).set(getHealthAsMetric(health));

				JSONArray peArray = (JSONArray) snapshotObject.get("pes");
				
				// Metrics to create
				long launchCount = 0;
				
				/* PE Loop */
				for (int i = 0; i < peArray.size(); i++) {
					JSONObject pe = (JSONObject) peArray.get(i);

					String peid = (String)pe.get("id");
					String resource = (String)pe.get("resource");

					// Capture peInfo for metrics
					HashMap<String, String> peInfo = new HashMap<String, String>();
					peInfo.put("status",(String)pe.get("status"));
					peInfo.put("health",(String)pe.get("health"));
					peInfo.put("resource",(String)pe.get("resource"));
					peInfoMap.put(peid, peInfo);

					mapOperatorKindAndPortNames(pe);

					
					launchCount = (long)pe.get("launchCount");
					
					metricsExporter.getStreamsMetric("launchCount",
							StreamsObjectType.PE,
							this.domain,
							instance,
							jobname,
							resource,
							peid).set(launchCount);	
				} // End pe loop
			} catch (ParseException e) {
				throw new IllegalStateException(e);
			}
		} // end if snapshot != null
	}

	/* Stop/unregister anything you need to */
	public void close() {
		removeExportedMetrics();
	}

	//public BigInteger getJobid() {
	//	return jobid;
	//}

	//public void setJobid(BigInteger jobid) {
	//	this.jobid = jobid;
	//}


	public String getJobMetrics() {
		return this.jobMetrics;
	}

	public void setJobMetrics(String jobMetrics) {
		this.jobMetrics = jobMetrics;
	}

	public String getJobid() {
		return this.jobid;
	}

	public void setJobid(String jobid) {
		this.jobid = jobid;
	}


	public String getJobSnapshot() {
		return jobSnapshot;
	}

	public void setJobSnapshot(String jobSnapshot) {
		this.jobSnapshot = jobSnapshot;
	}

	public String getInstance() {
		return instance;
	}

	public void setInstance(String instance) {
		this.instance = instance;
	}


	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getHealth() {
		return health;
	}

	public void setHealth(String health) {
		this.health = health;
	}

	public String getJobname() {
		return jobname;
	}

	public void setJobname(String jobname) {
		this.jobname = jobname;
	}

	/*
	 * getJobInfo Creates a JobInfo representation of this class with less
	 * information
	 */
	public JobInfo getJobInfo() {

		JobInfo ji = new JobInfo();
		/*
		ji.setAdlFile(adlFile);
		ji.setApplicationName(applicationName);
		ji.setApplicationPath(applicationPath);
		ji.setApplicationScope(applicationScope);
		ji.setApplicationVersion(applicationVersion);
		ji.setDataPath(dataPath);
		ji.setDomain(domain);
		ji.setHealth(health);
		ji.setId(getJobid());
		ji.setInstance(instance);
		ji.setJobGroup(jobGroup);
		ji.setJobMetrics(jobMetrics);
		ji.setLastMetricsFailure(lastMetricsFailure);
		ji.setLastMetricsRefresh(lastMetricsRefresh);
		ji.setLastMetricsRefreshFailed(lastMetricsRefreshFailed);
		ji.setLastSnapshotFailure(lastSnapshotFailure);
		ji.setLastSnapshotRefresh(lastSnapshotRefresh);
		ji.setLastSnapshotRefreshFailed(lastSnapshotRefreshFailed);
		ji.setName(name);
		ji.setOutputPath(outputPath);
		ji.setStartedByUser(startedByUser);
		ji.setStatus(status);
		ji.setSubmitTime(submitTime);
		//ji.setJobMetrics(resolvePortNames(jobMetrics));
		// Already resolved
		*/
		ji.setJobMetrics(jobMetrics);
		ji.setJobSnapshot(jobSnapshot);

		return ji;

	}



	@Override
	public String toString() {
		StringBuilder result = new StringBuilder();
		// String newline = System.getProperty("line.separator");
		result.append("Job name: " + this.getJobname());
		result.append(" Metrics: " + this.getJobMetrics());
		result.append(" Snapshot: " + this.getJobSnapshot());
		return result.toString();
	}

/*****************************************************************
 * Create Maps for easy reference when processing metrics
 * Some information is only found in the snapshot json
 *****************************************************************/

	private void mapOperatorKindAndPortNames(JSONObject peObject) {

		JSONArray operatorArray = (JSONArray) peObject.get("operators");
		
		/* Operator Loop */
		for (int i = 0; i < operatorArray.size(); i++) {
			JSONObject operator = (JSONObject) operatorArray.get(i);
			String operatorName = (String)operator.get("name");
			String operatorKind = (String)operator.get("operatorKind");
			operatorKindMap.put(operatorName,operatorKind);
			mapOperatorInputPortNames(operator, operatorName);
			mapOperatorOutputPortNames(operator, operatorName);
		}
	}

	// port maps <operatorname, map<indexWithinOperator,portname>>
	private void mapOperatorInputPortNames(JSONObject operator, String operatorName) {
		JSONArray inputPortsArray = (JSONArray) operator.get("inputPorts");
		Map<String,String> inputPortNames = new HashMap<String,String>();

		for (int i = 0; i < inputPortsArray.size(); i++) {
			JSONObject ip = (JSONObject) inputPortsArray.get(i);
			String indexWithinOperator = (String)ip.get("indexWithinOperator");
			String inputPortName = (String)ip.get("name");
			inputPortNames.put(indexWithinOperator,inputPortName);
		}
		operatorInputPortNames.put(operatorName,inputPortNames);
	}

	private void mapOperatorOutputPortNames(JSONObject operator, String operatorName) {
		JSONArray outputPortsArray = (JSONArray) operator.get("outputPorts");
		Map<String,String> outputPortNames = new HashMap<String,String>();

		for (int i = 0; i < outputPortsArray.size(); i++) {
			JSONObject ip = (JSONObject) outputPortsArray.get(i);
			String indexWithinOperator = (String)ip.get("indexWithinOperator");
			String outputPortName = (String)ip.get("name");
			outputPortNames.put(indexWithinOperator,outputPortName);
		}
		operatorOutputPortNames.put(operatorName,outputPortNames);
	}

/*
	private String resolveMappings(String metricsSnapshot) {
		if (metricsSnapshot != null) {

			JSONParser parser = new JSONParser();
			try {
				JSONObject metricsObject = (JSONObject) parser.parse(metricsSnapshot);

				JSONArray peArray = (JSONArray) metricsObject.get("pes");

				for (int i = 0; i < peArray.size(); i++) {
					JSONObject pe = (JSONObject) peArray.get(i);

					// Add resource name, status, and health
					//resolveResource(pe);
					enrichPEMetrics(pe);

					JSONArray operatorArray = (JSONArray) pe.get("operators");

					for (int j = 0; j < operatorArray.size(); j++) {
						JSONObject operator = (JSONObject) operatorArray.get(j);

						resolveOperatorInputPortNames(operator);
						resolveOperatorOutputPortNames(operator);
					}
				}

				metricsSnapshot = metricsObject.toJSONString();
			} catch (ParseException e) {
				throw new IllegalStateException(e);
			}
		}

		return metricsSnapshot;
	}
*/

/*
	@SuppressWarnings("unchecked")
	private void resolveOperatorInputPortNames(JSONObject operator) {
		JSONArray inputPortArray = (JSONArray) operator.get("inputPorts");

		if (inputPortArray == null) {
			return;
		}

		String operatorName = getOperatorName(operator);
		Map<String, String> inputPortNames = operatorInputPortNames.get(operatorName);

		if (inputPortNames == null) {
			return;
		}

		for (int i = 0; i < inputPortArray.size(); i++) {
			JSONObject inputPort = (JSONObject) inputPortArray.get(i);
			int portIndex = getOperatorPortIndex(inputPort);
			String portName = inputPortNames.get(portIndex);

			if (portName != null) {
				inputPort.put("name", portName);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void resolveOperatorOutputPortNames(JSONObject operator) {
		JSONArray outputPortArray = (JSONArray) operator.get("outputPorts");

		if (outputPortArray == null) {
			return;
		}

		String operatorName = getOperatorName(operator);
		Map<String, String> outputPortNames = operatorOutputPortNames.get(operatorName);

		if (outputPortNames == null) {
			return;
		}

		for (int i = 0; i < outputPortArray.size(); i++) {
			JSONObject outputPort = (JSONObject) outputPortArray.get(i);
			int portIndex = getOperatorPortIndex(outputPort);
			String portName = outputPortNames.get(portIndex);

			if (portName != null) {
				outputPort.put("name", portName);
			}
		}
	}


	@SuppressWarnings("unchecked")
	private void enrichPEMetrics(JSONObject metrics_pe) {
		String metrics_peid = metrics_pe.get("id").toString();
		if (this.jobSnapshot != null) {
			JSONParser parser = new JSONParser();
			try {
				JSONObject snapshotObject = (JSONObject) parser.parse(this.jobSnapshot);
				JSONArray peArray = (JSONArray) snapshotObject.get("pes");
				for (int i = 0; i < peArray.size(); i++) {
					JSONObject pe = (JSONObject) peArray.get(i);
					String peid = (String)pe.get("id");
					if (metrics_peid.equals(peid)) {
						LOGGER.debug("enrichPEMetrics: enriching metrics pe: " + peid + " from snapshot");
						LOGGER.debug("enrichPEMetrics: snapshot pe: " + peArray.get(i));
						metrics_pe.put("status",pe.get("status"));
						metrics_pe.put("health",pe.get("health"));
						metrics_pe.put("resource",pe.get("resource"));
					}
				}
			} catch (ParseException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	private String getOperatorName(JSONObject operator) {
		return operator.get("name").toString();
	}

	private int getOperatorPortIndex(JSONObject port) {
		return ((Number) port.get("indexWithinOperator")).intValue();
	}
*/
	private void createExportedMetrics() {
		LOGGER.trace("createExportedMetrics");
		// Create our own metrics that will be aggregates of Streams metrics
	    // PE, PE InputPort, PE OutputPort, PE Output Port Connection,
		// Operator, Operator InputPort, and Operator OutputPort metrics
		// are automatically created based on metrics discovered in json
		
		// job health
		metricsExporter.createStreamsMetric("healthy", StreamsObjectType.JOB, "Job health, set to 1 of job is healthy else 0");
		// job metrics
		metricsExporter.createStreamsMetric("nCpuMilliseconds", StreamsObjectType.JOB, "Sum of each pe metric: nCpuMilliseconds");
		metricsExporter.createStreamsMetric("nResidentMemoryConsumption", StreamsObjectType.JOB, "Sum of each pe metric: nResidentMemoryConsumption");
		metricsExporter.createStreamsMetric("nMemoryConsumption", StreamsObjectType.JOB, "Sum of each pe metric: nMemoryConsumption");
		metricsExporter.createStreamsMetric("avg_congestionFactor", StreamsObjectType.JOB, "Average of all pe connection metric: congestionFactor");
		metricsExporter.createStreamsMetric("max_congestionFactor", StreamsObjectType.JOB, "Maximum of all pe connection metric: congestionFactor");
		metricsExporter.createStreamsMetric("min_congestionFactor", StreamsObjectType.JOB, "Minimum of all pe connection metric: congestionFactor");
		metricsExporter.createStreamsMetric("sum_congestionFactor", StreamsObjectType.JOB, "Sum of each pe metric: congestionFactor (no value used by itself");
		metricsExporter.createStreamsMetric("pecount", StreamsObjectType.JOB, "Number of pes deployed for this job");
	}

	private void removeExportedMetrics() {
		// When this job is removed, remove all metrics for this job
		// (really its the specific instance of the metric for the streams objects of this job)
		LOGGER.trace("removeExportedMetrics()");
		metricsExporter.removeAllChildStreamsMetrics(this.domain, this.streamsInstanceName,this.jobname);
	}


	private void processMetrics(String jobMetrics) {
		/* Use this.jobMetrics to update the exported metrics */
		/* Some will be auto created, others we will control and aggregate */
		/* Specifically, we aggregate PE metrics to the job level */
		/* As the purpose of this is application metrics, PE level are not */
		/* understandable by operators, and future versions of streams */
		/* may create/remove pes automatically, and that would drive */
		/* a metric graphing tool crazy */
		
		/* Use SimpleJSON, it tests out pretty fast and easy to use */
		if (jobMetrics != null) {

			JSONParser parser = new JSONParser();
			try {
				JSONObject metricsObject = (JSONObject) parser.parse(this.jobMetrics);
				JSONArray peArray = (JSONArray) metricsObject.get("pes");
				
				// Job Metrics 
				long ncpu = 0, nrmc = 0, nmc = 0;
				long numconnections = 0, totalcongestion = 0, curcongestion = 0;
				long maxcongestion = 0 , avgcongestion = 0, mincongestion = 999;
				LOGGER.debug("Metrics, job status: " + this.getStatus());
				LOGGER.debug("Metrics, job helath: " + this.getHealth());
				// PE Loop 
				for (int i = 0; i < peArray.size(); i++) {
					JSONObject pe = (JSONObject) peArray.get(i);
					String peid = (String)pe.get("id");

					// Get info from peInfoMap
					Map<String,String> peInfo = peInfoMap.get(peid);

					String status = peInfo.get("status");
					String health = peInfo.get("health");
					String resource = peInfo.get("resource");

					LOGGER.debug("Metrics, pe: " + peid + " resource: " + resource);
					LOGGER.debug("Metrics, pe: " + peid + " status: " + status);
					LOGGER.debug("Metrics, pe: " + peid + " health: " + health);

					// If the PE is not healthy, then its resource may not be correct while it is being
					// relocated, and we cannot create / update those metrics
					if (!health.equalsIgnoreCase("healthy")) {
						LOGGER.info("Metrics, pe: " + peid + " is NOT healthy, NOT setting metrics");
						continue; // skip to next pe in loop
					}

					JSONArray peMetricsArray = (JSONArray) pe.get("metrics");
					/* PE Metrics Loop */
					for (int j = 0; j < peMetricsArray.size(); j++) {
						JSONObject metric = (JSONObject) peMetricsArray.get(j);
						String metricName = (String)metric.get("name");
						switch (metricName) {
						case "nCpuMilliseconds":
							LOGGER.debug("Metrics, pe: " + peid + " resource: " + resource + " nCpuMilliseconds: " + metric.get("value"));
							ncpu += (long)metric.get("value");
							break;
						case "nResidentMemoryConsumption":
							nrmc += (long)metric.get("value");
							break;
						case "nMemoryConsumption":
							nmc += (long)metric.get("value");
							break;
						}
						metricsExporter.getStreamsMetric(metricName,
								StreamsObjectType.PE,
								this.domain,
								this.streamsInstanceName,
								this.jobname,
								resource,
								peid).set((long)metric.get("value"));
					}
					
					/* PE inputPorts Loop */
					JSONArray inputPorts = (JSONArray) pe.get("inputPorts");
					for (int portnum = 0; portnum < inputPorts.size(); portnum++) {
						JSONObject port = (JSONObject)inputPorts.get(portnum);
						String indexWithinPE = Long.toString((long)port.get("indexWithinPE"));
						JSONArray metricsArray = (JSONArray) port.get("metrics");
						for (int m = 0; m < metricsArray.size(); m++) {
							JSONObject metric = (JSONObject) metricsArray.get(m);
							String metricName = (String)metric.get("name");
//							System.out.println("PE INPUT PORT METRIC: " + metricName);
							metricsExporter.getStreamsMetric(metricName,
									StreamsObjectType.PE_INPUTPORT,
									this.domain,
									this.streamsInstanceName,
									this.jobname,
									resource,
									peid,
									indexWithinPE).set((long)metric.get("value"));
						}	// End PE Input Ports Metrics Loop		
					} // End PE inputPorts loop			
									
					/* PE outputPorts Loop */
					JSONArray outputPorts = (JSONArray) pe.get("outputPorts");
					for (int portnum = 0; portnum < outputPorts.size(); portnum++) {
						JSONObject port = (JSONObject)outputPorts.get(portnum);
						
						String indexWithinPE = Long.toString((long)port.get("indexWithinPE"));
						JSONArray metricsArray = (JSONArray) port.get("metrics");
						for (int m = 0; m < metricsArray.size(); m++) {
							JSONObject metric = (JSONObject) metricsArray.get(m);
							String metricName = (String)metric.get("name");
//							System.out.println("PE OUTPUT PORT METRIC: " + metricName);
							metricsExporter.getStreamsMetric(metricName,
									StreamsObjectType.PE_OUTPUTPORT,
									this.domain,
									this.streamsInstanceName,
									this.jobname,
									resource,
									peid,
									indexWithinPE).set((long)metric.get("value"));
						}	// End PE Output Ports Metrics Loop		
						
						
						/* PE outputPorts Connections Loop */
						JSONArray connections = (JSONArray) port.get("connections");
						for (int con = 0; con < connections.size(); con++) {
							numconnections++;
							JSONObject connection = (JSONObject)connections.get(con);
							String connectionId = (String)connection.get("id");
							JSONArray cMetricsArray = (JSONArray) connection.get("metrics");
							for (int m = 0; m < cMetricsArray.size(); m++) {
								JSONObject metric = (JSONObject) cMetricsArray.get(m);
								String metricName = (String)metric.get("name");
								switch (metricName) {
								case "congestionFactor":
									curcongestion = (long)metric.get("value");
									totalcongestion += curcongestion;
									if (curcongestion > maxcongestion) maxcongestion = curcongestion;
									if (curcongestion < mincongestion) mincongestion = curcongestion;
								}
								metricsExporter.getStreamsMetric(metricName,
										StreamsObjectType.PE_OUTPUTPORT_CONNECTION,
										this.domain,
										this.streamsInstanceName,
										this.jobname,
										resource,
										peid,
										indexWithinPE,
										connectionId).set((long)metric.get("value"));								
							}
						} // End PE outputPort Connectdions Loop
					} // End PE outputPort loop

					/* PE operator Loop */
					JSONArray operatorArray = (JSONArray)pe.get("operators");
					for (int op = 0; op < operatorArray.size(); op++) {
						JSONObject operator = (JSONObject) operatorArray.get(op);
						//System.out.println(operator.toString());
						String operatorName = (String)operator.get("name");
						String operatorKind = this.operatorKindMap.get(operatorName);
//						System.out.println("OPERATOR NAME: " + operatorName);
						JSONArray opMetricsArray = (JSONArray) operator.get("metrics");

						/* Operator Metrics Loop, these are non-standard metrics */
						for (int om = 0; om < opMetricsArray.size(); om++) {
							JSONObject metric = (JSONObject) opMetricsArray.get(om);
							String operatorMetricName = (String)metric.get("name");
//							System.out.println("OPERATOR METRIC: " + operatorMetricName);
							switch (operatorMetricName) {
							default:
//								System.out.println("About to set " + operatorMetricName +
//										" using " + this.streamsInstanceName +
//										", " + name +
//										", " + operatorName +
//										" to: " + metric.get("value"));
								metricsExporter.getStreamsMetric(operatorMetricName,
										StreamsObjectType.OPERATOR,
										this.domain,
										this.streamsInstanceName,
										this.jobname,
										resource,
										peid,
										operatorName,
										operatorKind).set((long)metric.get("value"));
								break;
							}
						}	// End Operator Metrics Loop		
						
						// Loop over Operator Input Ports
						JSONArray opipArray = (JSONArray) operator.get("inputPorts");
						for (int opip = 0; opip < opipArray.size(); opip++) {
							JSONObject inputPort = (JSONObject)opipArray.get(opip);
							//System.out.println("INPUTPORT: " + inputPort.toString());
							String indexWithinOperator = (String)inputPort.get("indexWithinOperator");
							String inputPortName = this.operatorInputPortNames.get(operatorName).get(indexWithinOperator);
							//System.out.println("INPUTPORTNAME: " + inputPortName);
							JSONArray ipMetrics = (JSONArray)inputPort.get("metrics");
							for (int opipm = 0; opipm < ipMetrics.size(); opipm++) {
								JSONObject metric = (JSONObject) ipMetrics.get(opipm);
								String metricName = (String)metric.get("name");
								switch (metricName) {
								default:
									metricsExporter.getStreamsMetric(metricName,
											StreamsObjectType.OPERATOR_INPUTPORT,
											this.domain,
											this.streamsInstanceName,
											this.jobname,
											resource,
											peid,
											operatorName,
											operatorKind,
											inputPortName).set((long)metric.get("value"));
									break;
								}
							} // End Input Port Metrics Loop
						} // End Operator Input Port Loop

						// Loop over Operator Output Ports
						JSONArray opopArray = (JSONArray) operator.get("outputPorts");
						for (int opop = 0; opop < opopArray.size(); opop++) {
							JSONObject outputPort = (JSONObject)opopArray.get(opop);
							//System.out.println("OUTPUTPORT: " + outputPort.toString());
							String indexWithinOperator = (String)outputPort.get("indexWithinOperator");
							String outputPortName = this.operatorOutputPortNames.get(operatorName).get(indexWithinOperator);
							//System.out.println("OUTPUTPORTNAME: " + outputPortName);
							JSONArray opMetrics = (JSONArray)outputPort.get("metrics");
							for (int opopm = 0; opopm < opMetrics.size(); opopm++) {
								JSONObject metric = (JSONObject) opMetrics.get(opopm);
								String metricName = (String)metric.get("name");
								switch (metricName) {
								default:
									metricsExporter.getStreamsMetric(metricName,
											StreamsObjectType.OPERATOR_OUTPUTPORT,
											this.domain,
											this.streamsInstanceName,
											this.jobname,
											resource,
											peid,
											operatorName,
											operatorKind,
											outputPortName).set((long)metric.get("value"));
									break;
								}
							} // End Output Port Metrics Loop
						} // End Operator Output Port Loop						
						
					} // End Operator Loop
				} // End PE Loop
				metricsExporter.getStreamsMetric("pecount", StreamsObjectType.JOB,this.domain,this.streamsInstanceName, this.jobname).set(peArray.size());
				metricsExporter.getStreamsMetric("nCpuMilliseconds", StreamsObjectType.JOB,this.domain, this.streamsInstanceName,this.jobname).set(ncpu);
				metricsExporter.getStreamsMetric("nResidentMemoryConsumption", StreamsObjectType.JOB,this.domain, this.streamsInstanceName,this.jobname).set(nrmc);
				metricsExporter.getStreamsMetric("nMemoryConsumption", StreamsObjectType.JOB,this.domain,this.streamsInstanceName,this.jobname).set(nmc);
				if (numconnections > 0)
					avgcongestion = totalcongestion / numconnections;
				// else it was initialized to 0;
				metricsExporter.getStreamsMetric("sum_congestionFactor", StreamsObjectType.JOB,this.domain,this.streamsInstanceName, this.jobname).set(totalcongestion);
				metricsExporter.getStreamsMetric("avg_congestionFactor", StreamsObjectType.JOB,this.domain,this.streamsInstanceName,this.jobname).set(avgcongestion);
				metricsExporter.getStreamsMetric("max_congestionFactor", StreamsObjectType.JOB,this.domain,this.streamsInstanceName,this.jobname).set(maxcongestion);
				if (mincongestion == 999) mincongestion = 0;
				metricsExporter.getStreamsMetric("min_congestionFactor", StreamsObjectType.JOB,this.domain, this.streamsInstanceName,this.jobname).set(mincongestion);
			} catch (ParseException e) {
				throw new IllegalStateException(e);
			}
		} // end if metrics != null
	}
	

	/*
	// Update Exported metrics that are derived from information in the job snapshot (e.g. PE Launch Count)
	private void updateExportedSnapshotMetrics() {
		
		// Pull metrics from snapshot

		if (this.jobSnapshot != null) {

			JSONParser parser = new JSONParser();
			try {
				JSONObject snapshotObject = (JSONObject) parser.parse(this.jobSnapshot);

				String health = (String)snapshotObject.get("health");
				LOGGER.debug("snapshot Metrics job health: " + health);
				metricsExporter.getStreamsMetric("healthy", StreamsObjectType.JOB, this.domain, this.streamsInstanceName, this.name).set(getHealthAsMetric(health));



				JSONArray peArray = (JSONArray) snapshotObject.get("pes");
				
				// Metrics to create
				long launchCount = 0;
				
				
				for (int i = 0; i < peArray.size(); i++) {
					JSONObject pe = (JSONObject) peArray.get(i);
					String peid = (String)pe.get("id");
					String resource = (String)pe.get("resource");


					LOGGER.debug("Snapshots, pe: " + peid + " status: " + (String)pe.get("status"));
					LOGGER.debug("Snapshots, pe: " + peid + " helath: " + (String)pe.get("health"));
					LOGGER.debug("Snapshots, pe: " + peid + " resource: " + resource);

					
					launchCount = (long)pe.get("launchCount");
					
					metricsExporter.getStreamsMetric("launchCount",
							StreamsObjectType.PE,
							this.domain,
							this.streamsInstanceName,
							name,
							resource,
							peid).set(launchCount);	
				} // End pe loop
			} catch (ParseException e) {
				throw new IllegalStateException(e);
			}
		} // end if snapshot != null
	}

	*/

    private double getHealthAsMetric(String health) {
    	double value = 0;
    	switch (JobMXBean.Health.fromString(health)) {
    	case HEALTHY :
    		value = 1;
    		break;
    	case PARTIALLY_HEALTHY:
    	case PARTIALLY_UNHEALTHY:
			value = 0.5;
			break;
    	default:
    		value = 0;
		}
		LOGGER.debug("getHealthAsMetric(" + health + ") = " + value);
    	return value;
    }



}

# Dynatrace OneAgent	

## Overview

This bundle of advanced actions allows you to integrate [NeoLoad](https://www.neotys.com/neoload/overview) and Dynatrace OneAgent to improve the analysis of a load testing test.

This bundle has the following actions:  

* **DynatraceEvents**
  Links a load testing event to all services used by an Application monitored by Dynatrace  
  
* **DynatraceMonitoring**   
    * **Dynatrace -> NeoLoad**: Retrieve Infrastructure and Services metrics from DynaTrace and insert them in NeoLoad External Datas so that
      you can correlate NeoLoad and DynaTrace metrics within NeoLoad.
    * **NeoLoad -> Dynatrace**: Send the global statistics of the test to Dynatrace OneAgent so that it can be used as custom metrics 
      in Dynatrace dashboards.
      
     
| Property | Value |
| -----| -------------- |
| Maturity | Experimental |
| Author   | Neotys Partner Team |
| License  | [BSD Simplified](https://www.neotys.com/documents/legal/bsd-neotys.txt) |
| NeoLoad  | 6.3 (Enterprise or Professional Edition w/ Integration & Advanced Usage and NeoLoad Web option required)|
| Requirements | NeoLoad Web |
| Bundled in NeoLoad | No
| Download Binaries | See the [latest release](https://github.com/Neotys-Labs/Dynatrace/releases/latest)

## Installation

1. Download the [latest release](https://github.com/Neotys-Labs/Dynatrace/releases/latest)
1. Read the NeoLoad documentation to see [How to install a custom Advanced Action](https://www.neotys.com/documents/doc/neoload/latest/en/html/#25928.htm)

## Set-up

Once installed, how to use in a given NeoLoad project:

1. Create a User Path “Dynatrace”
1. Insert DynatraceEvents in the ‘End’ block.
1. Insert DynatraceMonitoring in the ‘Actions’ block.
1. Create a Population “Dynatrace” that contains 100% of User Path “Dynatrace”
1. In the Runtime section, select your scenario, select the “Dynatrace” population and define a constant load of 1 user.
   Do not use multiple load generator, good practice should be to keep only the local one.

## Parameters for Dynatrace Events

| Name             | Description |
| -----            | ----- |
| dynatraceId      |  Id of your saas Dynatrace environment (http://<id>.live.dynatrace.com) |
| dynatraceApiKey  |  API key of your Dynatrace account |
| tags (optional)  |  Dynatrace tags. link the event to all services having the specific tags (format: tag1,tag2) |
| proxyName (Optional) |  The name of the NeoLoad proxy to access to Dynatrace |
| dynatraceManagedHostname (Optional) | Hostname of your Dynatrace managed environment |

## Parameters for Dynatrace Monitoring

Tip: Get NeoLoad API information in NeoLoad preferences: Project Preferences / REST API.

| Name             | Description |
| -----            | ----- |
| dynatraceId      |  Id of your saas dynatrace environment (http://<id>.live.dynatrace.com) |
| dynatraceApiKey  | API key of your dynatrace account |
| tags (optional)  | Dynatrace tags. Link the sending monitoring data to Dynatrace tags (format: tag1,tag2) |
| dynatraceManagedHostname (Optional) | Hostname of your Dynatrace managed environment |
| dataExchangeApiUrl   | Where the DataExchange server is located. Typically the NeoLoad controller |
| dataExchangeApiKey  (Optional)  | API key of the DataExchange API   |
| proxyName (Optional) |  The name of the NeoLoad proxy to access to Dynatrace |
  
## Status Codes

* NL-DYNATRACE_EVENT_ACTION-01: Could not parse arguments on dynatrace event
* NL-DYNATRACE_EVENT_ACTION-02: Technical Error encouter on dynatrace event
* NL-DYNATRACE_MONITORING_ACTION-01: Could not parse arguments on dynatrace monitoring
* NL-DYNATRACE_MONITORING_ACTION-02: Technical Error encouter on dynatrace monitoring

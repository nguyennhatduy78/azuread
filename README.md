# AzureAD User Migration
Author: Q15452
## Introduction
- Provide functions to creating, updating and deleting users read from .csv files
- Support multiple profiles depends on department
- Support dynamic field mapping and other configurations
- Support data customization and validation with external JS scripts
- Support batch requests to optimize number of requests send to Azure ( At the moment the limit requests for 1 batch is 20 requests)
- Write statistics to log and send to pre-configured email
## Basic configuration
### Field Mapping
Field mapping **MUST** follow this form: **azureField - csvField** 
- azureFields in mapping **MUST** be **EXACT** azure field belonging to Graph User entity. For example: userPrincipalName, mailNickname, companyName, etc...All the User properties can be found here [User Properties](https://docs.microsoft.com/en-us/graph/api/resources/user?view=graph-rest-1.0#properties)

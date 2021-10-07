# AZUREAD USER MIGRATION
Author: Q15452
## INTRODUCTION
- Provide functions to creating, updating and deleting users read from .csv files
- Support multiple profiles depends on department
- Support dynamic field mapping and other configurations
- Support data customization and validation with external JS scripts
- Support batch requests to optimize number of requests send to Azure ( At the moment the limit requests for 1 batch is 20 requests)
- Write statistics to log and send to pre-configured email
## Basic configuration
All options below can be configured in ***application-{profile_name}.yml***, which can be found [here](https://github.com/nguyennhatduy78/azuread/blob/fb97a7881f1e359106b63ea927f4a162dd70d3b3/src/main/resources/application-PS.yml)
### Field Mapping
Field mapping **MUST** follow this form: **azureField - csvField** 
- azureFields in mapping **MUST** be **EXACT** azure field belonging to Graph User entity. For example: ***userPrincipalName, mailNickname, companyName,*** etc... All the User properties can be found here [User Properties](https://docs.microsoft.com/en-us/graph/api/resources/user?view=graph-rest-1.0#properties)
- There are 3 fields **MUST NOT** be empty: ***userPrincipalName, mailNickname, displayName*** when creating and 1 field ***userPrincipalName*** when updating. These fields must be in the mapping configuration to map with csv fields or get value from JS customization later. 
- Other customized data comes under **additionalUserdataProcessor** section.
- Prototype user mapping can be found [here](https://github.com/nguyennhatduy78/azuread/blob/fb97a7881f1e359106b63ea927f4a162dd70d3b3/src/main/resources/application-PS.yml#L48)
### Data customization
Data from csv can be overrided by customized data from JS script. Here are some rules when customizing data using JS Nashorn function:
- Path to JS script is configured in application-{profile_name}.yml, under **path.nashorn** section.
- A customize function should look like this : 
```
function userPrincipalName(userdata){
  //Some logic processing 
  return customizedData;
}
```
- The name of function **MUST** be the name of **azureField** that needs customizing.
- ***userdata*** is a map that contains data of a user from csv in the form of (azureField - value) 
- **JS Nashorn is only able to return String value**
- Prototype JS Nashorn Customization can be found [here](https://github.com/nguyennhatduy78/azuread/blob/fb97a7881f1e359106b63ea927f4a162dd70d3b3/src/main/java/com/canon/cusa/scripts/CustomizeField.js)
## HOW-TO
### Installing
Module can be installed easily from maven by executing command below in project root folder: 
```
mvn install
```
### Run 
After installing, ***.jar*** file can be found in ***/target*** under root project folder. To run this ***.jar***, executing command below
```
java -jar {Name-of-jar}.jar --spring.profiles.active={Name-of-profile} --spring.config.location={Path-to-config-folder}  {Path-to-csvfile}
```
in which: 
- ***Name-of-jar***:  the name of the jar file
- ***Name-of-profile***: the name of profile, which can be found in ***Resources*** folder and its filename should look like this 
```
application-{Name-of-Profile}.yml
```
- ***Path-to-config-folder***: path to the folder containing configuration files.
- ***Path-to-csvfile***: path to the .csv file


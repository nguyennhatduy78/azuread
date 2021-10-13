package com.canon.cusa.services;

import com.canon.cusa.utils.AuthenticatedClient;
import com.canon.cusa.mapping.UserMapping;
import com.canon.cusa.utils.Configuration;
import com.canon.cusa.utils.EmailClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.microsoft.graph.content.BatchRequestContent;
import com.microsoft.graph.content.BatchResponseContent;
import com.microsoft.graph.http.HttpMethod;
import com.microsoft.graph.models.*;
import com.microsoft.graph.requests.GraphServiceClient;
import com.microsoft.graph.requests.UserCollectionPage;
import com.opencsv.CSVWriter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

@Service
@EnableConfigurationProperties(UserMapping.class)
@Slf4j
public class UserService {

    private final UserMapping userMapping;
    private final GraphServiceClient client;
    private final Configuration config;
    private final EmailClient emailClient;

    public UserService(UserMapping userMapping, AuthenticatedClient authenticatedClient, Configuration config, EmailClient emailClient){
        this.userMapping = userMapping;
        this.client = authenticatedClient.getClient();
        this.config = config;
        this.emailClient = emailClient;
    }

    @Value("{$defaultPassword}")
    private String defaultPassword;
    @Value("${skip-create}")
    private Boolean skipCreate;
    public void createUser(List<Map<String, String>> users, BatchRequestContent userRequests, Map<String, Map<String, String>> batchList) {
        if(skipCreate){
            log.info("Creating user function is now disabled!!!");
            Map<String, String> createRequestIDs = new HashMap<>();
            for (Map<String, String> userdata : users) {
                String principalnameForSearching = userdata.get("userPrincipalName");
                if(principalnameForSearching.contains("#EXT#")){
                    principalnameForSearching = principalnameForSearching.replace("#EXT#", "%23EXT%23");
                }
                createRequestIDs.put("false_"+userdata.get("userPrincipalName")
                        ,userRequests.addBatchRequestStep(client.users(principalnameForSearching).buildRequest(),HttpMethod.GET));
            }
            batchList.put("create", createRequestIDs);
        }else {
            final String FIELD_SKIPPED_REGEX = "accountEnabled|manager";
            Map<String, String> createRequestIDs = new HashMap<>();
            for (Map<String, String> userdata : users) {
                try{
                    User user = new User();
                    Class<? extends User> userClass = user.getClass();
                    PasswordProfile passwordProfile = new PasswordProfile();
                    passwordProfile.password = defaultPassword;
                    passwordProfile.forceChangePasswordNextSignIn = Boolean.valueOf(config.getAzureConfig().get("forceChangePasswordNextSignIn"));
                    user.passwordProfile = passwordProfile;
                    if(userdata.get("accountEnabled") != null){
                        user.accountEnabled = Boolean.valueOf(userdata.get("accountEnabled"));
                    } else {
                        user.accountEnabled = true;
                    }
                    if(userdata.containsKey("manager_id")){
                        DirectoryObject manager = new DirectoryObject();
                        manager.id = userdata.get("manager_id");
//                        user.manager = manager;
                        try{
                            client.users(userdata.get("manager_id")).manager().reference().buildRequest().put(manager);
                        } catch (Exception e){
                            log.debug("Manager {} fail to process for user {}", userdata.get("manager"), userdata.get("userPrincipalName"));
                        }
                    }
                    processingAdditionalUserdataLogic(user, userdata);
                    userdata.forEach((azureField,data)->{
                        if (azureField.matches(FIELD_SKIPPED_REGEX)) return;
                        setField(user, userClass, azureField, data);
                    });
                    createRequestIDs.put(user.accountEnabled+"_"+userdata.get("userPrincipalName")
                            ,userRequests.addBatchRequestStep(client.users().buildRequest(),HttpMethod.POST,user));
                } catch (Exception e){
                    log.debug("User {} error: {}",userdata.get("userPrincipalName"),e.getMessage());
                }
            }
            batchList.put("create", createRequestIDs);
        }
    }

    public void updateUser(List<Map<String, String>> users, BatchRequestContent userRequests, Map<String, Map<String,String>> batchList) {
        final String FIELD_SKIPPED_REGEX = "userPrincipalName|mailNickname|deleteFlag|accountEnabled|currentEmail|manager";
        Map<String, String> modifiedRequestIDs = new HashMap<>();
        for (Map<String, String> userdata : users) {
            try{
                User user = new User();
                Class<? extends User> userClass = user.getClass();
                String userPrincipalNameForRequest = userdata.get("userPrincipalName");
                if(userPrincipalNameForRequest.contains("#EXT#")){
                    userPrincipalNameForRequest = userPrincipalNameForRequest.replace("#EXT#", "%23EXT%23");
                }
                boolean delete = false;
                if(userdata.containsKey("deleteFlag")){
                   delete = Boolean.parseBoolean(userdata.get("deleteFlag"));
                }
                if(userdata.containsKey("accountEnabled")){
                    user.accountEnabled = Boolean.valueOf(userdata.get("accountEnabled"));
                }
                if(userdata.containsKey("manager_id")){
                    DirectoryObject manager = new DirectoryObject();
                    manager.id = userdata.get("manager_id");
//                    user.manager = manager;
                    try{
                        client.users(userdata.get("manager_id")).manager().reference().buildRequest().put(manager);
                    } catch (Exception e){
                        log.debug("Manager {} fail to process for user {}", userdata.get("manager"), userdata.get("userPrincipalName"));
                    }
                }
                if(delete){
                    modifiedRequestIDs.put(user.accountEnabled+"_"+userdata.get("userPrincipalName")
                            ,userRequests.addBatchRequestStep(client.users(userPrincipalNameForRequest).buildRequest(), HttpMethod.DELETE));
                    continue;
                }
                processingAdditionalUserdataLogic(user, userdata);
                userdata.forEach((azureField,data)->{
                    if (azureField.matches(FIELD_SKIPPED_REGEX)) return;
                    setField(user, userClass, azureField, data);
                });
                modifiedRequestIDs.put(user.accountEnabled+"_"+userdata.get("userPrincipalName")
                        ,userRequests.addBatchRequestStep(client.users(userPrincipalNameForRequest).buildRequest(), HttpMethod.PATCH, user));
            } catch (Exception e){
                log.info("User {} error: {}", userdata.get("userPrincipalName"), e.getMessage());
            }
        }
        batchList.put("modify", modifiedRequestIDs);
    }

    @Value("${date-format}")
    private String dateFormat;
    private void setField(User user, Class<? extends User> userClass, String azureField, String data){
        Optional<Field> field = checkField(userClass, azureField );
        if(field.isPresent()){
            try{
                if(field.get().getType() == List.class){
                    field.get().set(user, Collections.singletonList(data));
                } else if(field.get().getType() == OffsetDateTime.class){
                    OffsetDateTime dateTime = OffsetDateTime.of( LocalDate.parse(data, DateTimeFormatter.ofPattern(dateFormat))
                            .atStartOfDay(), OffsetDateTime.now().getOffset());
                    field.get().set(user, dateTime);
                } else if(field.get().getType() == Object.class){
                    log.warn("Object value not supported for {}", azureField);
                } else{
                    field.get().set(user, data);
                }
            } catch (Exception e){
                log.debug("Field access error: {}",e.getMessage());
            }
        } else {
            if(azureField.split("_")[0].equals("extension")){
                user.additionalDataManager().put(azureField,new JsonPrimitive(data));
            }
        }
    }

    private void setManager(User user, Map<String,String> userdata){
    }

    //----------------------------------------------------------------------------
    //User processing functions
    public Map<String, List<Map<String, String>>> getUsersForCreateAndUpdate(Map<String, Map<String, String>> csvData){
        BatchRequestContent requests = new BatchRequestContent();
        BatchRequestContent requestsForManager = new BatchRequestContent();
        Map<String,String> batchList = new HashMap<>();
        Map<String,String> batchListForManager = new HashMap<>();
        Map<String, List<Map<String, String>>> validatedData = new HashMap<>(Integer.parseInt(config.getAzureConfig().get("requestLimit")));
        List<Map<String, String>> usersForUpdate = new ArrayList<>(Integer.parseInt(config.getAzureConfig().get("requestLimit")));
        List<Map<String, String>> usersForCreate = new ArrayList<>(Integer.parseInt(config.getAzureConfig().get("requestLimit")));
        csvData.forEach((principalName, user)->{
            String filter = "startsWith(userPrincipalName,'"+user.get("uid")+"')";
            batchList.put(principalName,requests.addBatchRequestStep(client.users().buildRequest().filter(filter)));
            if(user.containsKey("manager")){
                String filterForManager = "startsWith(userPrincipalName,'"+user.get("manager")+"')";
                batchListForManager.put(principalName, requestsForManager.addBatchRequestStep(client.users().buildRequest().filter(filterForManager)));
            }
        });

        if(requestsForManager.requests != null){
            try{
                BatchResponseContent responsesForManager = client.batch().buildRequest().post(requestsForManager);
                batchListForManager.forEach((principalName, requestID) -> {
                    JsonArray array = Objects.requireNonNull(Objects.requireNonNull(responsesForManager.getResponseById(requestID)).body).getAsJsonObject().get("value").getAsJsonArray();
                    if(array.size() == 1){
                        JsonElement user = array.get(0);
                        csvData.get(principalName).put("manager_id", user.getAsJsonObject().get("id").getAsString());
                    }else{
                        //TODO: log manager not found
                    }
                });
            } catch (Exception e){
                log.debug("Manager requests error: {}", e.getMessage());
            }
        }
        try{
            BatchResponseContent responses = client.batch().buildRequest().post(requests);
            batchList.forEach((principalName,requestID) -> {
                assert responses != null;
                JsonArray array = Objects.requireNonNull(Objects.requireNonNull(responses.getResponseById(requestID)).body).getAsJsonObject().get("value").getAsJsonArray();
                if(array.size() == 1){
                    JsonElement user = array.get(0);
                    if(user.getAsJsonObject().get("mail") != null) {
                        csvData.get(principalName).put("currentEmail", user.getAsJsonObject().get("mail").getAsString());
                    }else {
                        csvData.get(principalName).put("currentEmail", "");
                        //TODO: check
                    }
                    usersForUpdate.add(csvData.get(principalName));
                }else if(array.size() == 0){
                    usersForCreate.add(csvData.get(principalName));
                } else {
                    log.info("Duplicated users found: {}",principalName);
                }
            });
        } catch (Exception e){
            log.debug("Response error: {}", e.getMessage());
        }
        validatedData.put("update", usersForUpdate);
        validatedData.put("create", usersForCreate);
        return validatedData;
    }

    public Optional<List<Map<String, String>>> preprocessCSVData(String[][] rawData){
        try {
            List<Map<String, String>> csvData = new ArrayList<>();
            final int numberOfFields = rawData[0].length;
            final int numberOfRecords = rawData.length-1;
            for(int i = 1; i<=numberOfRecords; i++){
                Map<String, String> tmp = new HashMap<>();
                for(int j = 0; j<numberOfFields; j++){
                    tmp.put(rawData[0][j], rawData[i][j]);
                }
                csvData.add(tmp);
            }
            return Optional.of(csvData);
        } catch (Exception e){
            log.debug("Raw CSV Data failed to pre-process: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Value("${domainSuffix}")
    private String domainSuffix;
    public Optional<Map<String, Map<String, String>>> customizeAdditionalUserdata(List<Map<String, String>> csvDataPreprocessed){
        try{
            Map<String, String> mapper = userMapping.getMapper();
            Map<String, Map<String, String>> csvData = new HashMap<>(Integer.parseInt(config.getAzureConfig().get("requestLimit")));
            List<String> customizedAzureField = new ArrayList<>();
            List<String> invalidUsers = new ArrayList<>(Integer.parseInt(config.getAzureConfig().get("requestLimit")));
            csvDataPreprocessed.forEach(userData ->{
                Map<String, String> tmp = new HashMap<>();
                tmp.put("domainSuffix", domainSuffix);
                if(mapper.containsKey("userPrincipalName")){
                    tmp.put("userPrincipalName", "");
                }
                if(mapper.containsKey("mailNickname")){
                    tmp.put("mailNickname", "");
                }
                if(mapper.containsKey("displayName")){
                    tmp.put("displayName","");
                }
                if(mapper.containsKey("accountEnabled")){
                    tmp.put("accountEnabled", "");
                }
                mapper.forEach((azureField, csvField)->{
                    if(userData.get(csvField) != null)
                        if (!userData.get(csvField).trim().equals(""))
                            tmp.put(azureField, userData.get(csvField));
                });
                tmp.forEach((azureField, data) -> {
                    Optional<String> customizeValue = customizeFieldValue(azureField, tmp);
                    if(customizeValue.isPresent()){
                        if(!customizedAzureField.contains(azureField))
                            customizedAzureField.add(azureField);
                        tmp.replace(azureField, customizeValue.get());
                    }
                });
                if(!validateAzureData(tmp)) {
                    invalidUsers.add(tmp.get("userPrincipalName"));
                    return;
                }
                csvData.put(tmp.get("userPrincipalName"), tmp);
            });
            if(csvData.isEmpty())
                throw new Exception("CSV Data empty");
            if(!invalidUsers.isEmpty()){
                log.info("List of invalid use(s): {}",invalidUsers.size());
                invalidUsers.forEach(principalName -> log.info("+ {}", principalName.split("@")[0]));
            }
            log.info("List of Azure Field being customized: {} field(s)", customizedAzureField.size());
            customizedAzureField.forEach(field -> {
                log.info("+ {}", field);
            });
            return Optional.of(csvData);
        } catch (Exception e){
            log.debug("Additional userdata failed to customize: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Field> checkField(Class userClass, String name){
        try {
            return Optional.of(userClass.getField(name));
        }catch (NoSuchFieldException e){
            return Optional.empty();
        }
    }

    //Nashorn functions
    @Value("${path.nashorn}")
    private String scriptPath;
    private Optional<String> customizeFieldValue(String field, Map<String,String> userdata){
        try {
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("nashorn");
            engine.eval(new FileReader(scriptPath));
            Object result = ((Invocable) engine).invokeFunction(field, userdata);
            return Optional.ofNullable(result.toString());
        } catch(Exception e){
            log.debug("Nashorn script error: {}", e.getMessage());
            return Optional.empty();
        }
    }

    //Additional logic process
    private void processingAdditionalUserdataLogic(User user, Map<String,String> userdata){
        Map<String,String> additionalLogicField = userMapping.getAdditionalLogic();
        List<String> logicOrder = userMapping.getAdditionalLogicOrder();
        Map<String,String> tmpData = new HashMap<>(logicOrder.size());
        tmpData.put("currentEmail", userdata.get("currentEmail"));
        tmpData.put("newEmail", userdata.get("mail"));
        tmpData.put("city", userdata.get("city"));
        tmpData.put("state", userdata.get("state"));
        logicOrder.forEach(logicField -> {
            String extensionField = additionalLogicField.get(logicField);
            Optional<String> value = customizeFieldValue(logicField, tmpData);
            if(value.isPresent()){
                tmpData.put(logicField, value.get());
                user.additionalDataManager().put(extensionField, new JsonPrimitive(value.get()));
            }
        });
    }

    private Boolean validateAzureData(Map<String,String> userdata){
        try{
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("nashorn");
            engine.eval(new FileReader(scriptPath));
            return (Boolean) ((Invocable) engine).invokeFunction("validate", userdata);
        } catch (Exception e){
            log.debug("nashorn engine validate error: {}", e.getMessage());
            return false;
        }
    }

    //Final Result Processing functions
    @Value("${path.sumary-logs}")
    private String logsPath;
    @Value("${log-filename}")
    private String logsName;
    @Value("${mail.subject}")
    private String subject;
    @Value("${mail.tomail}")
    private String receiver;
    @Value("${mail.content}")
    private String content;
    public void processFinalResult(List<Map<String,Map<String,String>>> batches, List<BatchResponseContent> responses, Map<String, Map<String, String>> users){
        log.info("-----------------------------------");
        log.info("Final result: ");
        List<String> createUsers = new ArrayList<>(20);
        List<String> modifiedUsers = new ArrayList<>(20);
        List<String> disabledUsers= new ArrayList<>(20);
        List<String> enabledUsers = new ArrayList<>(20);
        List<String> failUsers = new ArrayList<>(20);
        int totalUser = 0;
        for (int i = 0; i < batches.size() ; i++) {
            totalUser += batches.get(i).size();
            classifyResult(responses.get(i),batches.get(i),createUsers,modifiedUsers,disabledUsers, enabledUsers,failUsers, users);
        }
        log.info("Total number of users processed: "+ totalUser);
        log.info("+ Number of created users: {}",createUsers.size());
        log.info("+ Number of modified users: {}", modifiedUsers.size());
        log.info("+ Number of disabled users: {}",disabledUsers.size());
        log.info("+ Number of enabled users: {}",enabledUsers.size());
        log.info("+ Number of Failed users: {}",failUsers.size());
        log.info("-----------------------------------");
        log.info("Created users: ");
        log.info(String.join(",", createUsers));
        log.info("-------------");
        log.info("Modified users: ");
        log.info(String.join(",",modifiedUsers));
        log.info("-------------");
        log.info("Disabled users: ");
        log.info(String.join(",",disabledUsers));
        log.info("-------------");
        log.info("Enabled users: ");
        log.info(String.join(",",enabledUsers));
        log.info("-------------");
        log.info("Failed users: ");
        log.info(String.join(",",failUsers));
        log.info("-------------");
        String logs = "*******************************************************"+"\r\n"+
                "Users Processed by IdM Feed from PeopleSoft"+"\r\n"+
                "*******************************************************"+"\r\n"+
                "Total Number of Users Processed (Created + Modified + Failed): "+ totalUser+"\r\n" +
                "Number of Users Created: "+createUsers.size()+"\r\n"+
                "Number of Users Modified: "+ modifiedUsers.size()+"\r\n"+
                "Number of Users Enabled: "+enabledUsers.size()+"\r\n"+
                "Number of Users Disabled: "+disabledUsers.size()+"\r\n"+
                "Number of Users Failed: "+failUsers.size()+"\r\n"+
                "*******************************************************"+"\r\n"+
                "Users Created: " + "\r\n"+String.join(";", createUsers)+"\r\n"+
                "*******************************************************"+"\r\n"+
                "Users Modified: " + "\r\n"+ String.join(";",modifiedUsers)+"\r\n"+
                "*******************************************************"+"\r\n"+
                "Users Enabled: " + "\r\n"+ String.join(";",enabledUsers)+"\r\n"+
                "*******************************************************"+"\r\n"+
                "Users Disabled: " + "\r\n"+ String.join(";",disabledUsers)+"\r\n"+
                "*******************************************************"+"\r\n"+
                "Users Failed: " + "\r\n"+ String.join(";",failUsers);
        processingLogs(logs);
    }

    private void classifyResult(BatchResponseContent response,
                                Map<String,Map<String,String>> batch,
                                List<String> createUsers,
                                List<String> modifiedUsers,
                                List<String> disabledUsers,
                                List<String> enabledUsers,
                                List<String> failUsers,
                                Map<String,Map<String,String>> users){
        if(batch.containsKey("create")){
            batch.get("create").forEach((username, requestID)-> {
                String validate = username.substring(0, 6);
                try{
                    response.getResponseById(requestID).getDeserializedBody(User.class);
                    if(validate.contains("true")){
                        enabledUsers.add(username.substring(5).split("_")[0]
                                +" - "+users.get(username.substring(5)).get("displayName"));
                        createUsers.add(username.substring(5).split("_")[0]
                                +" - "+users.get(username.substring(5)).get("displayName"));
                    } else {
                        disabledUsers.add(username.substring(6).split("_")[0]
                                +" - "+users.get(username.substring(6)).get("displayName"));
                        createUsers.add(username.substring(6).split("_")[0]
                                +" - "+users.get(username.substring(6)).get("displayName"));
                    }
                } catch (Exception e){
                    e.printStackTrace();
                    failUsers.add(username.substring(username.split("_")[0].length()+1).split("@")[0]
                            +" - "+users.get(username.substring(username.split("_")[0].length()+1)).get("displayName"));
                    log.debug("User {} error: {}",username, e.getMessage());
                }
            });
        }
        if(batch.containsKey("modify")){
            batch.get("modify").forEach((username, requestID)->{
                String validate = username.substring(0, 6);
                try{
                    response.getResponseById(requestID).getDeserializedBody(User.class);
                    if(validate.contains("true")){
                        enabledUsers.add(username.substring(5).split("_")[0]
                                +" - "+users.get(username.substring(5)).get("displayName"));
                        modifiedUsers.add(username.substring(5).split("_")[0]
                                +" - "+users.get(username.substring(5)).get("displayName"));
                    } else {
                        disabledUsers.add(username.substring(6).split("_")[0]
                                +" - "+users.get(username.substring(6)).get("displayName"));
                        modifiedUsers.add(username.substring(6).split("_")[0]
                                +" - "+users.get(username.substring(6)).get("displayName"));
                    }
                } catch (Exception e){
                    e.printStackTrace();
                    failUsers.add(username.substring(username.split("_")[0].length()+1).split("@")[0]
                            +" - "+users.get(username.substring(username.split("_")[0].length()+1)).get("displayName"));
                    log.debug("User {} error: {}",username, e.getMessage());
                }
            });
        }
    }


    private void processingLogs(String logs){
        log.info("Now export to logs at {}", logsPath);
        log.info("Sending logs to {}......", receiver);

        String date = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").format(LocalDateTime.now());
        String path = logsPath + "/"+logsName+"."+date+".log";
        try{
            FileWriter writer = new FileWriter(path);
            writer.write(logs);
            writer.close();
            emailClient.sendEmail(receiver, Collections.singletonList(path), content, subject);
        } catch (Exception e){

        }
    }

}


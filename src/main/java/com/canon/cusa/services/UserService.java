package com.canon.cusa.services;

import com.canon.cusa.utils.AuthenticatedClient;
import com.canon.cusa.mapping.UserMapping;
import com.canon.cusa.utils.Configuration;
import com.canon.cusa.utils.EmailClient;
import com.google.gson.JsonPrimitive;
import com.microsoft.graph.content.BatchRequestContent;
import com.microsoft.graph.content.BatchResponseContent;
import com.microsoft.graph.http.HttpMethod;
import com.microsoft.graph.models.Extension;
import com.microsoft.graph.models.OpenTypeExtension;
import com.microsoft.graph.models.PasswordProfile;
import com.microsoft.graph.models.User;
import com.microsoft.graph.requests.GraphServiceClient;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

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
            final String FIELD_SKIPPED_REGEX = "accountEnabled";
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
        final String FIELD_SKIPPED_REGEX = "userPrincipalName|mailNickname|deleteFlag|accountEnabled|currentEmail";
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
                } else {
                    user.accountEnabled = true;
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

    private void setField(User user, Class<? extends User> userClass, String azureField, String data){
        Optional<Field> field = checkField(userClass, azureField );
        if(field.isPresent()){
            try{
                if(field.get().getType() == List.class){
                    field.get().set(user, Collections.singletonList(data));
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

    //----------------------------------------------------------------------------
    //User processing functions
    public Map<String, List<Map<String, String>>> getUsersForCreateAndUpdate(Map<String, Map<String, String>> csvData){
        BatchRequestContent batchRequestContent = new BatchRequestContent();
        Map<String,String> batchList = new HashMap<>();
        Map<String, List<Map<String, String>>> validatedData = new HashMap<>(Integer.parseInt(config.getAzureConfig().get("requestLimit")));
        List<Map<String, String>> usersForUpdate = new ArrayList<>(Integer.parseInt(config.getAzureConfig().get("requestLimit")));
        List<Map<String, String>> usersForCreate = new ArrayList<>(Integer.parseInt(config.getAzureConfig().get("requestLimit")));
        csvData.keySet().forEach(principalName -> {
            String principalNameForSearching = principalName.replace("#EXT#", "%23EXT%23");
            batchList.put(principalName, batchRequestContent.addBatchRequestStep(client.users(principalNameForSearching).buildRequest()));
        });
        BatchResponseContent batchResponseContent = client.batch().buildRequest().post(batchRequestContent);
        StringBuffer userUpdate = new StringBuffer();
        StringBuffer userCreate = new StringBuffer();
        batchList.forEach((principalName,requestID) -> {
            try {
                User user = Objects.requireNonNull(batchResponseContent.getResponseById(requestID)).getDeserializedBody(User.class);
                csvData.get(principalName).put("currentEmail",user.mail);
                usersForUpdate.add(csvData.get(principalName));
                userUpdate.append(principalName).append(", ");
            } catch (Exception e){
                usersForCreate.add(csvData.get(principalName));
                userCreate.append(principalName).append(", ");
            }
        });
        validatedData.put("update", usersForUpdate);
        validatedData.put("create", usersForCreate);
        log.debug("Validated users: ");
        log.debug("+ User found for update: {}",userUpdate);
        log.debug("+ User found for create: {}",userCreate);
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
                mapper.forEach((azureField, csvField)->{
                    if(userData.get(csvField)!= null){
                        tmp.put(azureField, userData.get(csvField));
                    } else {
                        tmp.put(azureField,"");
                    }
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
        String logs = "Total number of users processed: "+ totalUser+"\r\n" +
                "+ Number of created users: "+createUsers.size()+"\r\n"+
                "+ Number of modified users: "+ modifiedUsers.size()+"\r\n"+
                "+ Number of disabled users: "+disabledUsers.size()+"\r\n"+
                "+ Number of enabled users: "+enabledUsers.size()+"\r\n"+
                "+ Number of failed users: "+failUsers.size()+"\r\n"+
                "-----------------------------------"+"\r\n"+
                "+ Created users: " + String.join(",", createUsers)+"\r\n"+
                "+ Modified users: " + String.join(",",modifiedUsers)+"\r\n"+
                "+ Disabled users: " + String.join(",",disabledUsers)+"\r\n"+
                "+ Enabled users: " + String.join(",",enabledUsers)+"\r\n"+
                "+ Failed users: " + String.join(",",failUsers);
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
                        enabledUsers.add(username.substring(5).split("@")[0]
                                +" - "+users.get(username.substring(5)).get("displayName"));
                        createUsers.add(username.substring(5).split("@")[0]
                                +" - "+users.get(username.substring(5)).get("displayName"));
                    } else {
                        disabledUsers.add(username.substring(6).split("@")[0]
                                +" - "+users.get(username.substring(6)).get("displayName"));
                        createUsers.add(username.substring(6).split("@")[0]
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
                        enabledUsers.add(username.substring(5).split("@")[0]
                                +" - "+users.get(username.substring(5)).get("displayName"));
                        modifiedUsers.add(username.substring(5).split("@")[0]
                                +" - "+users.get(username.substring(5)).get("displayName"));
                    } else {
                        disabledUsers.add(username.substring(6).split("@")[0]
                                +" - "+users.get(username.substring(6)).get("displayName"));
                        modifiedUsers.add(username.substring(6).split("@")[0]
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


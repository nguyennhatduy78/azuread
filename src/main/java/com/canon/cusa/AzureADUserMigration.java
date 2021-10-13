package com.canon.cusa;

import com.canon.cusa.services.UserService;

import com.canon.cusa.utils.AuthenticatedClient;
import com.canon.cusa.utils.Configuration;
import com.microsoft.graph.content.BatchRequestContent;
import com.microsoft.graph.content.BatchResponseContent;
import com.microsoft.graph.models.DirectoryObject;
import com.microsoft.graph.requests.GraphServiceClient;
import com.opencsv.CSVReader;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

@SpringBootApplication
@Slf4j
public class AzureADUserMigration implements CommandLineRunner {

    private final UserService userService;
    private final Configuration config;
    private final GraphServiceClient client;
    public AzureADUserMigration(UserService userService, Configuration config, AuthenticatedClient authenticatedClient){
        this.config = config;
        this.userService = userService;
        this.client = authenticatedClient.getClient();
    }

    public static void main(String[] args) {
        SpringApplication.run(AzureADUserMigration.class, args);
    }


    @Override
    public void run(String... args) {
        log.info("AzureADUserMigration version: {}", config.getApplicationConfig().get("version"));
        if(args.length == 0){
            log.info("No arguments found!!");
            return;
        }
        final String filePath = args[0];
        try {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            CSVReader reader = new CSVReader(new FileReader(filePath));
            String[] headers = reader.readNext();
            List<Map<String, Map<String,String>>> batches = new ArrayList<>();
            List<BatchResponseContent> responses = new ArrayList<>();
            Map<String, Map<String,String>> users = new HashMap<>();

            final int total = (int) Files.lines(Paths.get(filePath)).count()-1;
            final int limit = Integer.parseInt(config.getAzureConfig().get("requestLimit"));
            final int loops = total/limit;
            int count = total;

            log.info("Azure API batch request limit: {}", limit);
            log.info("CSV file found at {}", filePath);
            log.info("Number of records found: {}, divided into {} session(s)",total, loops+1);
            //Start the loops
            for (int i = 0; i < loops+1; i++) {
                BatchRequestContent userRequests = new BatchRequestContent();
                Map<String, Map<String,String>> batch = new HashMap<>();
                Map<String, String> managers = new HashMap<>();
                Future<Map<Integer,Map<String, Map<String, String>>>> rawData = executor.submit(new ReadData(
                        count,
                        i,
                        limit,
                        reader,
                        headers,
                        total,
                        loops,
                        userService));
                Map<Integer, Map<String, Map<String, String>>> userdata = rawData.get();
                count = (int) userdata.keySet().toArray()[0];
                final int tmpCount = count;
                Map<String, List<Map<String, String>>> validatedData = executor.submit(() -> userService.getUsersForCreateAndUpdate(userdata.get(tmpCount))).get();
                executor.submit(new UpdateTask(userService,validatedData.get("update"),userRequests, batch, managers));
                executor.submit(new CreateTask(userService, validatedData.get("create"), userRequests, batch, managers));
                BatchResponseContent response = executor.submit(() -> client.batch().buildRequest().post(userRequests)).get();
                executor.submit(()->userService.processingManagerRequests(managers));
                validatedData.get("update").forEach(user -> users.put(user.get("userPrincipalName"), user));
                validatedData.get("create").forEach(user -> users.put(user.get("userPrincipalName"), user));
                responses.add(response);
                batches.add(batch);
            }
            executor.submit(()->userService.processFinalResult(batches, responses, users));
            executor.submit(() -> {
                log.info("All tasks finished. Now exit......");
                executor.shutdown();
                System.exit(0);
            });
        } catch(Exception e){
            log.debug("CSV error : {}", e.getMessage());
            e.printStackTrace();
        }

    }
}

@Slf4j
class ReadData implements Callable<Map<Integer,Map<String, Map<String, String>>>> {
    private Integer count;
    private final Integer i;
    private final Integer limit;
    private final CSVReader reader;
    private final String[] headers;
    private final Integer total;
    private final Integer loops;
    private final UserService userService;

    ReadData(int count, int session, int limit, CSVReader reader, String[] headers, Integer total, Integer loops, UserService userService) {
        this.count = count;
        this.i = session;
        this.limit = limit;
        this.reader = reader;
        this.headers = headers;
        this.total = total;
        this.loops = loops;
        this.userService = userService;
    }

    @Override
    public Map<Integer,Map<String,Map<String, String>>> call() throws Exception {
        log.info("-----------------------------------");
        log.info("Session: {}", i+1);
        int size = limit+1;
        if(i.equals(loops))
            size = total - loops*limit+1;
        String[][] rawData = new String[size][headers.length];
        int start = 1;
        rawData[0] = headers;
        while(start <= limit){
            if(count == 0){
                break;
            }
            rawData[start] = reader.readNext();
            start++;
            count--;
        }
        Map<Integer, Map<String, Map<String, String>>> result = new HashMap<>();
        Optional<List<Map<String, String>>> preprocessCSVData = userService.preprocessCSVData(rawData);
        if(preprocessCSVData.isPresent()){
            Optional<Map<String, Map<String, String>>> customizeAdditionalUserdata = userService.customizeAdditionalUserdata(preprocessCSVData.get());
            customizeAdditionalUserdata.ifPresent(users -> result.put(count, users));
        }
        return result;
    }
}

@Slf4j
class UpdateTask implements Runnable{

    private final UserService userService;
    private final List<Map<String,String>> data;
    private final BatchRequestContent userRequests;
    private final Map<String, Map<String,String>> requestIDs;
    private final Map<String,String> managers;

    UpdateTask(UserService userService, List<Map<String, String>> data, BatchRequestContent userRequests, Map<String, Map<String, String>> requestIDs, Map<String, String> managers) {
        this.userService = userService;
        this.data = data;
        this.userRequests = userRequests;
        this.requestIDs = requestIDs;
        this.managers = managers;
    }

    @Override
    public void run() {
        if(!data.isEmpty()){
            userService.updateUser(data,userRequests, requestIDs, managers);
        }
    }
}

@Slf4j
class CreateTask implements Runnable {
    private final UserService userService;
    private final List<Map<String,String>> data;
    private final BatchRequestContent userRequests;
    private final Map<String, Map<String,String>> requestIDs;
    private final Map<String,String> managers;

    CreateTask(UserService userService, List<Map<String, String>> data, BatchRequestContent userRequests, Map<String, Map<String, String>> requestIDs, Map<String, String> managers) {
        this.userService = userService;
        this.data = data;
        this.userRequests = userRequests;
        this.requestIDs = requestIDs;
        this.managers = managers;
    }
    @Override
    public void run() {
        if(!data.isEmpty()){
            userService.createUser(data,userRequests,requestIDs, managers);
        }
    }
}




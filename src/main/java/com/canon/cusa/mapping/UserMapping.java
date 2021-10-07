package com.canon.cusa.mapping;


import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.core.parameters.P;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@ConfigurationProperties(prefix = "mapping")
public class UserMapping {
    private List<Map<String, String>> users;
    private List<Map<String,String>> additionalUserdataProcessor;

    public void setUsers(List<Map<String, String>> users) {
        this.users = users;
    }

    public void setAdditionalUserdataProcessor(List<Map<String, String>> additionalUserdataProcessor) {
        this.additionalUserdataProcessor = additionalUserdataProcessor;
    }

    public Map<String, String> getMapper(){
        return this.mapper();
    }
    public Map<String,String> getAdditionalLogic(){
        return this.additionalLogicProcessor();
    }
    public List<String> getAdditionalLogicOrder(){return this.additionalLogicOrder(); }
    private Map<String, String> mapper(){
        Map<String, String> mapper = new HashMap<>();
        try {
            this.users.forEach(mapper::putAll);
        }catch (Exception e){
            log.info("User mapping error: {}", e.getMessage());
        }
        return mapper;
    }

    private Map<String, String> additionalLogicProcessor(){
        Map<String, String> additionalLogicProcessor = new HashMap<>();
        try {
            this.additionalUserdataProcessor.forEach(additionalLogicProcessor::putAll);
        }catch (Exception e){
            log.info("User mapping error: {}", e.getMessage());
        }
        return additionalLogicProcessor;
    }
    private List<String> additionalLogicOrder(){
        List<String> order = new ArrayList<>();
        try {
            this.additionalUserdataProcessor.forEach(logic -> order.add((String) logic.keySet().toArray()[0]));
        }catch (Exception e){
            log.info("User mapping error: {}", e.getMessage());
        }
        return order;
    }
}

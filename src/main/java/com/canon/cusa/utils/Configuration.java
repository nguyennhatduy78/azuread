package com.canon.cusa.utils;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@Slf4j
@ConfigurationProperties(prefix = "config")
public class Configuration {
    private List<Map<String, String>> application ;
    private List<Map<String,String>> azure;
    public Map<String, String> getApplicationConfig(){
        Map<String,String> config = new HashMap<>();
        try{
            this.getApplication().forEach(config::putAll);
        }catch (Exception e){
            log.debug("Application config error: {}", e.getMessage());
        }
        return config;
    }
    public Map<String, String> getAzureConfig(){
        Map<String,String> config = new HashMap<>();
        try{
            this.getAzure().forEach(config::putAll);
        }catch (Exception e){
            log.debug("Application config error: {}", e.getMessage());
        }
        return config;
    }

}

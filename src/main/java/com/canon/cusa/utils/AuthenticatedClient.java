package com.canon.cusa.utils;


import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;

import com.microsoft.graph.authentication.TokenCredentialAuthProvider;
import com.microsoft.graph.requests.GraphServiceClient;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AuthenticatedClient  {

    private GraphServiceClient client;

    public GraphServiceClient getClient() {
        return this.client;
    }

    public AuthenticatedClient(@Value("${azure.client-id}")String clientId,
                               @Value("${azure.tenant-id}")String tenantId,
                               @Value("${azure.client-secret}")String clientSecret,
                               @Value("${env-credentials}") String env) {
         if(Boolean.parseBoolean(env)){
             clientId = System.getenv("client_id");
             tenantId = System.getenv("tenant_id");
             clientSecret = System.getenv("client_secret");
         }
        try {
            final ClientSecretCredential clientSecretCredential = new ClientSecretCredentialBuilder()
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .tenantId(tenantId)
                    .build();
            final TokenCredentialAuthProvider tokenCredentialAuthProvider = new TokenCredentialAuthProvider(clientSecretCredential);
            this.client = GraphServiceClient
                    .builder()
                    .authenticationProvider(tokenCredentialAuthProvider)
                    .buildClient();
        } catch (Exception e){
            log.debug("Authentication error: {}", e.getMessage());
        }
    }
}

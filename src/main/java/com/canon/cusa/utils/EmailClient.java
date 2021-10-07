package com.canon.cusa.utils;

import java.io.File;
import java.util.List;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.Authenticator;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
@Slf4j
public class EmailClient {
  private Session session;
  private String sendFrom;
  
  public EmailClient(
          @Value("${mail.frommail}") String username,
          @Value("${mail.password}") String password,
          @Value("${mail.host}") String host,
          @Value("${mail.portssl}") String port) {
    this.sendFrom = username;
    try {
    // Get system properties
    Properties properties = System.getProperties();

    // Setup mail server
    properties.setProperty("mail.smtp.auth", "true");
    properties.setProperty("mail.smtp.starttls.enable", "true");
    properties.setProperty("mail.smtp.host", host);
    properties.setProperty("mail.smtp.port", port);
    // Get the default Session object.
      this.session = Session.getInstance(properties, new Authenticator() {
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
          return new PasswordAuthentication(username, password);
        }
      });
    }catch (Exception e) {
      log.debug("Email fail to connect: {}", e.getMessage());
    }
  }


  public void sendEmail(String sendTo, List<String> files, String messageBody, String subject) {

    try {
      // Create a default MimeMessage object.
      MimeMessage message = new MimeMessage(this.session);

      // Set From: header field of the header.
      message.setFrom(new InternetAddress(sendFrom));

      // Set To: header field of the header.
      message.addRecipient(Message.RecipientType.TO, new InternetAddress(sendTo));

      // Set Subject: header field
      message.setSubject(subject);

   // Create the message part 
      BodyPart messageBodyPart = new MimeBodyPart();

      // Fill the message
      messageBodyPart.setText(messageBody);
      
      // Create a multipart message
      Multipart multipart = new MimeMultipart();

      // Set text message part
      multipart.addBodyPart(messageBodyPart);

      // Part two is attachment
      for (String file : files) {
        messageBodyPart = new MimeBodyPart();
        String filename = new File(file).getName();
        DataSource source = new FileDataSource(file);
        messageBodyPart.setDataHandler(new DataHandler(source));
        messageBodyPart.setFileName(filename);
        multipart.addBodyPart(messageBodyPart);
      }

      // Send the complete message parts
      message.setContent(multipart);

      // Send message
      Transport.send(message);
      log.info("Sent message successfully....");
    } catch (MessagingException mex) {
      mex.printStackTrace();
    }
  }
}

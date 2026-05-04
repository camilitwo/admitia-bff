package cl.mtn.admitiabff.service.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

@Component
public class SesEmailSender {
    private static final Logger log = LoggerFactory.getLogger(SesEmailSender.class);

    private final SesClient sesClient;
    private final String from;

    public SesEmailSender(SesClient sesClient, @Value("${app.email.from:no-reply@mtn.cl}") String from) {
        this.sesClient = sesClient;
        this.from = from;
    }

    public String send(String to, String subject, String body) {
        SendEmailRequest request = SendEmailRequest.builder()
                .source(from)
                .destination(Destination.builder().toAddresses(to).build())
                .message(Message.builder()
                        .subject(Content.builder().data(subject).charset("UTF-8").build())
                        .body(Body.builder()
                                .html(Content.builder().data(body).charset("UTF-8").build())
                                .text(Content.builder().data(stripHtml(body)).charset("UTF-8").build())
                                .build())
                        .build())
                .build();
        SendEmailResponse response = sesClient.sendEmail(request);
        log.info("SES email sent to {} messageId={}", to, response.messageId());
        return response.messageId();
    }

    private String stripHtml(String body) {
        return body == null ? "" : body.replaceAll("<[^>]+>", "");
    }
}


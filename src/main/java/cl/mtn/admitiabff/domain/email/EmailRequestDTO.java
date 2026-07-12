package cl.mtn.admitiabff.domain.email;

import cl.mtn.admitiabff.domain.notification.EmailTemplate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@Builder
public class EmailRequestDTO {

        public final String template;
        public final String to;
        public final String subject;
        public final String recipientType;
        public final Long recipientId;
        public final Map<String, Object> data;


        /*public static Builder builder() { return new Builder(); }*/
/*
        public static final class Builder {
            private EmailTemplate template;
            private String to;
            private String subject;
            private String recipientType;
            private Long recipientId;
            private Map<String, Object> data;
            public Builder template(EmailTemplate t) { this.template = t; return this; }
            public Builder to(String to) { this.to = to; return this; }
            public Builder subject(String s) { this.subject = s; return this; }
            public Builder recipientType(String t) { this.recipientType = t; return this; }
            public Builder recipientId(Long id) { this.recipientId = id; return this; }
            public Builder data(Map<String, Object> d) { this.data = d; return this; }
            public EmailRequest build() { return new EmailRequest(this); }
        }*/
}

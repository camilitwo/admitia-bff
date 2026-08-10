package cl.mtn.admitiabff.prekinder.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderOpenApiConfig {
    @Bean
    GroupedOpenApi prekinderApi() {
        return GroupedOpenApi.builder().group("prekinder").pathsToMatch("/api/prekinder/**").build();
    }

    @Bean
    OpenAPI prekinderOpenApiDefinition() {
        return new OpenAPI()
            .info(new Info().title("Admitia Prekínder API").version("v1")
                .description("Contrato backend para las consolas administrativas, evaluadoras y familiares de Prekínder."))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
            .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}

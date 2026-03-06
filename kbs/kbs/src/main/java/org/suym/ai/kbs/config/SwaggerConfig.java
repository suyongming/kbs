package org.suym.ai.kbs.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI 配置类
 * 访问地址: http://localhost:10483/swagger-ui/index.html
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI kbsOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("企业知识库系统 API")
                        .description("基于 Spring Boot + Milvus + LangChain4j 的 RAG 知识库系统接口文档")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("SU_KBS Team")
                                .email("support@kbs.com")
                                .url("https://github.com/suym/kbs"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("http://springdoc.org")));
    }
}

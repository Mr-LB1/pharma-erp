package com.pharma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 医药 B2B ERP 启动入口。
 * 运行环境要求：JDK 17（本机使用本地 .tools/jdk-17，不改系统 JAVA_HOME）。
 */
@SpringBootApplication
public class PharmaApplication {

    public static void main(String[] args) {
        SpringApplication.run(PharmaApplication.class, args);
    }
}

package com.hospital.rag;

import com.hospital.rag.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * HIS-RAG 服务启动类。
 *
 * <p>面向医院信息系统（HIS）的检索增强生成（RAG）服务，包含：
 * 文档切分、向量化、混合检索（向量 + BM25）、LLM 重排、答案生成与隐私脱敏。</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(RagProperties.class)
public class HisRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(HisRagApplication.class, args);
    }
}

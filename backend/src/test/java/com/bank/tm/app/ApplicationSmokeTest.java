package com.bank.tm.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"tm.data-dir=target/test-data", "tm.pipeline.worker-threads=1"})
@AutoConfigureMockMvc
class ApplicationSmokeTest {

    @Autowired
    MockMvc mvc;

    @Test
    void metaAndSampleEndpoints() throws Exception {
        mvc.perform(get("/api/meta")).andExpect(status().isOk())
                .andExpect(jsonPath("$.stages.length()").value(12))
                .andExpect(jsonPath("$.llm.serverProvider").value("mock"));
        mvc.perform(get("/api/samples")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/knowledge/search").param("q", "SQL enjeksiyonu")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chunk.id").exists());
        mvc.perform(post("/api/reviews/from-sample/kurumsal-talimat-api").contentType("application/json")
                        .content("{\"llmMode\":\"SERVER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").exists());
        mvc.perform(get("/api/reviews/does-not-exist")).andExpect(status().isNotFound());
    }

    @Test
    void settingsEndpointsMaskSecretsAndBlockCloud() throws Exception {
        mvc.perform(get("/api/settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.llm.apiKey").value(""))
                .andExpect(jsonPath("$.policy.blockPublicEndpoints").value(true));
        String cloud = SETTINGS.replace("\"enabled\":false,\"baseUrl\":\"\"", "\"enabled\":true,\"baseUrl\":\"https://api.openai.com/v1\"")
                .replace("\"model\":\"\"}", "\"model\":\"text-embedding-3-small\"}");
        mvc.perform(put("/api/settings").contentType("application/json").content(cloud)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings").contentType("application/json").content(SETTINGS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.pipeline.threatBatchSizeServer").value(8))
                .andExpect(jsonPath("$.secrets['llm.apiKey'].set").value(true))
                .andExpect(jsonPath("$.settings.llm.apiKey").value(""));
        mvc.perform(get("/api/settings/history")).andExpect(status().isOk()).andExpect(jsonPath("$[0].changedBy").exists());
        mvc.perform(post("/api/settings/reset")).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.pipeline.threatBatchSizeServer").value(12));
    }

    private static final String SETTINGS = """
            {"llm":{"provider":"mock","baseUrl":"http://genai.bank.local/v1","apiKey":"test-anahtar-123456","model":"m","timeoutSeconds":60,
              "structuredOutputMode":"JSON_SCHEMA","maxAttempts":2,"taskModels":{}},
             "embedding":{"enabled":false,"baseUrl":"","apiKey":"","model":""},
             "browserLlm":{"enabled":true,"defaultModel":"Qwen2.5-3B-Instruct-q4f16_1-MLC","configUrl":"/webllm-config.json"},
             "extraction":{"browserPdfText":true,"ocrMode":"BROWSER","ocrLanguages":"tur+eng","tesseractWorkerPath":"",
              "tesseractCorePath":"","tesseractLangPath":""},
             "pipeline":{"defaultLlmMode":"SERVER","temperature":0.1,"modelExtractionMaxCharsServer":60000,
              "modelExtractionMaxCharsBrowser":8000,"threatBatchSizeServer":8,"threatBatchSizeBrowser":4,
              "retrievalPerCandidate":4,"retrievalMaxPerSource":2}}
            """;
}

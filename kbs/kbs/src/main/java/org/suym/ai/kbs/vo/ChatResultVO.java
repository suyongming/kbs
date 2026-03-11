package org.suym.ai.kbs.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResultVO {
    private String answer;
    
    @JsonProperty("source_docs")
    private List<String> sourceDocs;
}

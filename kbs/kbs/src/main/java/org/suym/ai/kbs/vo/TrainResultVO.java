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
public class TrainResultVO {
    @JsonProperty("total_items")
    private int totalItems;
    
    @JsonProperty("success_count")
    private int successCount;
    
    @JsonProperty("success_skus")
    private List<String> successSkus;
    
    @JsonProperty("failed_count")
    private int failedCount;
    
    @JsonProperty("failed_lines")
    private List<String> failedLines;
}

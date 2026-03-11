package org.suym.ai.kbs.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageSearchVO {
    private Double score;
    
    @JsonProperty("image_path")
    private String imagePath;
    
    private String description;
}

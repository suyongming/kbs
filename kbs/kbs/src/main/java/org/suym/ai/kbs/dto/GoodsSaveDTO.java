package org.suym.ai.kbs.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoodsSaveDTO {
    
    private String id;
    
    private String sku;
    
    private String name;
    
    private Double price;
    
    private Integer status;
    
    @JsonProperty("image_path")
    private String imagePath;
    
    private String description;
}

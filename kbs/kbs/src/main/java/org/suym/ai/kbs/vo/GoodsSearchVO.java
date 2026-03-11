package org.suym.ai.kbs.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoodsSearchVO {
    private Long id;
    private Double score;
    private String text;
    private Map<String, Object> metadata;
}

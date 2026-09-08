package com.stock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An optional, whole-group-togglable set of {@link ParamDto} entries (identified via
 * {@link ParamDto#getGroup()}) within a strategy's GET /api/strategies catalogue entry — see
 * specs/backend/strategy-scan.md, "選用參數群組". Currently only REBOUND's "rise" group. The wire
 * field is literally named "default", a reserved word in Java, hence the {@code @JsonProperty} on
 * {@link #isDefaultOn()}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ParamGroupDto {

    private String code;
    private String name;
    private Boolean defaultOn;

    public ParamGroupDto() {
    }

    public ParamGroupDto(String code, String name, Boolean defaultOn) {
        this.code = code;
        this.name = name;
        this.defaultOn = defaultOn;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @JsonProperty("default")
    public Boolean isDefaultOn() {
        return defaultOn;
    }

    @JsonProperty("default")
    public void setDefaultOn(Boolean defaultOn) {
        this.defaultOn = defaultOn;
    }
}

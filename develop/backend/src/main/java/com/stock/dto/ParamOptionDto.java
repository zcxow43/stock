package com.stock.dto;

/**
 * One selectable option of a {@code type: "multiSelect"} {@link ParamDto} — currently only
 * `investors`'s `FOREIGN`/`TRUST` pair (specs/backend/strategy-scan.md, "presets 與 params 的關係").
 */
public class ParamOptionDto {

    private String code;
    private String name;

    public ParamOptionDto() {
    }

    public ParamOptionDto(String code, String name) {
        this.code = code;
        this.name = name;
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
}

package com.stock.dto;

import java.math.BigDecimal;

/**
 * `detail` of one VOLUME_SURGE hit — see specs/backend/strategy-scan.md, "量開始變多". `volume`/
 * `averageVolume`/`increasePercent` describe the signal day D (`averageVolume` is the mean of D-1..D-5
 * rounded half-up to whole shares, `increasePercent` is {@code (volume / baseline - 1) * 100} to two
 * decimals); the `prev*` trio is the same for D-1 against its own baseline D-2..D-6.
 * {@code prevIncreasePercent} is {@code null} when D-1's baseline is zero. The judgment itself always
 * uses the un-rounded values; these are reference figures. Always serialized, {@code null} included.
 */
public class VolumeSurgeDetailDto {

    private final long volume;
    private final long averageVolume;
    private final BigDecimal increasePercent;
    private final long prevVolume;
    private final long prevAverageVolume;
    private final BigDecimal prevIncreasePercent;

    public VolumeSurgeDetailDto(long volume, long averageVolume, BigDecimal increasePercent, long prevVolume,
                                 long prevAverageVolume, BigDecimal prevIncreasePercent) {
        this.volume = volume;
        this.averageVolume = averageVolume;
        this.increasePercent = increasePercent;
        this.prevVolume = prevVolume;
        this.prevAverageVolume = prevAverageVolume;
        this.prevIncreasePercent = prevIncreasePercent;
    }

    public long getVolume() {
        return volume;
    }

    public long getAverageVolume() {
        return averageVolume;
    }

    public BigDecimal getIncreasePercent() {
        return increasePercent;
    }

    public long getPrevVolume() {
        return prevVolume;
    }

    public long getPrevAverageVolume() {
        return prevAverageVolume;
    }

    public BigDecimal getPrevIncreasePercent() {
        return prevIncreasePercent;
    }
}

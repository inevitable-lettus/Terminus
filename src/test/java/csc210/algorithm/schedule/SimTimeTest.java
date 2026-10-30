package csc210.algorithm.schedule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimTimeTest {

    @Test
    void readsBothForms() {
        assertEquals(21600, SimTime.parse("21600"));
        assertEquals(21600, SimTime.parse("06:00:00"));
        assertEquals(64800 + 61, SimTime.parse("18:01:01"));
        assertEquals(0, SimTime.parse("0"));
    }

    @Test
    void refusesAnythingElse() {
        for (String bad : new String[] {null, "", "6:00", "06:60:00", "86400", "24:00:00", "-5", "noon"}) {
            assertThrows(IllegalArgumentException.class, () -> SimTime.parse(bad), String.valueOf(bad));
        }
    }
}

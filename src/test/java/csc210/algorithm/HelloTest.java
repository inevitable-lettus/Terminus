package csc210.algorithm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HelloTest {

    @Test
    void greetReturnsExpectedMessage() {
        assertEquals("Hello, CSC210 algorithm module", Hello.greet());
    }
}

package csc210.algorithm.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionsTest {

    private static Options parse(String... args) {
        return Options.parse(args);
    }

    @Test
    void noArgumentsAndTheHelpSpellingsAllGiveHelp() {
        assertEquals(Options.Mode.HELP, parse().mode);
        assertEquals(Options.Mode.HELP, parse("help").mode);
        assertEquals(Options.Mode.HELP, parse("--help").mode);
        assertEquals(Options.Mode.HELP, parse("-h").mode);
    }

    @Test
    void aBareCommandNeedsNothingElse() {
        Options options = parse("network");

        assertEquals(Options.Mode.NETWORK, options.mode);
        assertEquals(Options.DEFAULT_SANDBOX, options.sandboxUrl);
        assertFalse(options.start, "the default is to read the run that is already going");
        assertFalse(options.timeGiven, "the default is to leave the run's clock alone");
        assertNull(options.minutes);
        assertNull(options.routes);
        assertNull(options.walkMetres);
        assertEquals(15, options.top);
        assertEquals("out", options.outDirectory);
    }

    @Test
    void everyCommandIsRecognised() {
        assertEquals(Options.Mode.STOPS, parse("stops").mode);
        assertEquals(Options.Mode.EXPORT, parse("export").mode);
        Options journey = parse("journey", "5", "217");
        assertEquals(Options.Mode.JOURNEY, journey.mode);
        assertEquals(5, journey.from);
        assertEquals(217, journey.to);
    }

    @Test
    void publishIsAnAliasOfExportThatRemembersItWasUsed() {
        Options options = parse("publish");

        assertEquals(Options.Mode.EXPORT, options.mode);
        assertTrue(options.usedPublishAlias);
        assertFalse(parse("export").usedPublishAlias);
    }

    @Test
    void theOldAttachFlagIsAcceptedAndChangesNothing() {
        Options options = parse("network", "--attach");

        assertFalse(options.start);
        assertEquals(Options.Mode.NETWORK, options.mode);
    }

    @Test
    void aSeedImpliesStartingARunButATimeAloneDoesNot() {
        assertTrue(parse("network", "--seed", "8230").start);
        assertEquals("8230", parse("network", "--seed", "8230").seed);
        assertTrue(parse("network", "--start").start);

        Options timed = parse("network", "--time", "08:30:00");
        assertFalse(timed.start, "a time only asks to look at another moment of the current run");
        assertTrue(timed.timeGiven);
        assertEquals("08:30:00", timed.time);
    }

    @Test
    void budgetOverridesAreCarriedThrough() {
        Options options = parse("network", "--minutes", "12", "--routes", "5", "--walk", "180.5");

        assertEquals(12, options.minutes);
        assertEquals(5, options.routes);
        assertEquals(180.5, options.walkMetres);
    }

    @Test
    void optionsMayFollowTheJourneyIds() {
        Options options = parse("journey", "0", "119", "--time", "08:30:00", "--sandbox", "http://elsewhere:9000");

        assertEquals("http://elsewhere:9000", options.sandboxUrl);
        assertEquals("08:30:00", options.time);
    }

    @Test
    void badUsageIsRejectedWithSomethingReadable() {
        assertThrows(IllegalArgumentException.class, () -> parse("dance"));
        assertThrows(IllegalArgumentException.class, () -> parse("journey", "0"));
        assertThrows(IllegalArgumentException.class, () -> parse("journey", "a", "b"));
        assertThrows(IllegalArgumentException.class, () -> parse("network", "--time"));
        assertThrows(IllegalArgumentException.class, () -> parse("network", "--nonsense", "1"));
    }

    @Test
    void numbersAndTimesAreValidatedWhereTheyAreParsed() {
        for (String[] bad : new String[][]{
                {"network", "--time", "6pm"}, {"network", "--time", "25:00:00"}, {"network", "--time", "08:60:00"},
                {"network", "--minutes", "0"}, {"network", "--minutes", "-3"}, {"network", "--minutes", "many"},
                {"network", "--routes", "0"}, {"network", "--walk", "-1"}, {"network", "--walk", "NaN"},
                {"stops", "--top", "0"}}) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> Options.parse(bad),
                    String.join(" ", bad));
            assertTrue(thrown.getMessage().startsWith("--"), thrown.getMessage());
        }
    }

    @Test
    void zeroMetresOfWalkingIsAllowedBecauseItMeansOnlyTheStopItself() {
        assertEquals(0.0, parse("network", "--walk", "0").walkMetres);
    }

    @Test
    void usageNamesEveryCommandAndTheTokenRoute() {
        String usage = Options.usage();
        for (String word : new String[]{"network", "stops", "journey", "export", "--start", "DSTNS_OPERATOR_TOKEN", "--token-file"}) {
            assertTrue(usage.contains(word), "usage does not mention " + word);
        }
    }
}

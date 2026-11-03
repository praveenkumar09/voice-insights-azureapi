package com.aia.voiceinsights.api.service;

import static org.junit.jupiter.api.Assertions.*;

import com.aia.voiceinsights.api.model.CustomerProfile;
import com.aia.voiceinsights.api.service.CustomerMemoryService.Person;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomerMemoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T04:00:00Z");

    private static CustomerProfile debrief(String id, String name, int daysAgo) {
        CustomerProfile p = new CustomerProfile();
        p.setId(id);
        p.setCustomerName(name);
        p.setRawTranscript("[Advisor] talked about the meeting");
        p.setCreatedAt(NOW.minus(daysAgo, ChronoUnit.DAYS));
        return p;
    }

    private static List<Person> people(CustomerProfile... all) { return CustomerMemoryService.group(List.of(all)); }

    // ── grouping ──

    @Test
    void severalDebriefsOfOnePersonAreOnePersonNewestFirst() {
        List<Person> ps = people(debrief("a", "Nick Tan", 10), debrief("b", "Nick Tan", 1), debrief("c", "Nick Tan", 5));
        assertEquals(1, ps.size());
        assertEquals("b", ps.get(0).latest().getId());
        assertEquals(3, ps.get(0).debriefs().size());
    }

    @Test
    void aFirstNameOnlyDebriefJoinsTheOnlyPersonWithThatFirstName() {
        List<Person> ps = people(debrief("a", "Nick", 8), debrief("b", "Nick Tan", 2));
        assertEquals(1, ps.size());
        assertEquals("Nick Tan", ps.get(0).name());
        assertEquals(2, ps.get(0).debriefs().size());
    }

    @Test
    void aFirstNameOnlyDebriefIsNotGuessedWhenTwoPeopleShareIt() {
        List<Person> ps = people(debrief("a", "Nick", 8), debrief("b", "Nick Tan", 2), debrief("c", "Nick Lee", 3));
        assertEquals(3, ps.size());
    }

    @Test
    void debriefsWithoutANameOrAnythingSaidAreLeftOut() {
        CustomerProfile nameless = debrief("a", " ", 1);
        CustomerProfile silent = debrief("b", "Nick", 1);
        silent.setRawTranscript("");
        assertTrue(people(nameless, silent).isEmpty());
    }

    // ── matching what was heard ──

    @Test
    void aUniqueFirstNameFindsThePerson() {
        List<Person> found = CustomerMemoryService.find(people(debrief("a", "Nick Tan", 1), debrief("b", "Priya Nair", 2)), "Nick");
        assertEquals(1, found.size());
        assertEquals("Nick Tan", found.get(0).name());
    }

    @Test
    void twoPeopleWithTheSameFirstNameAreBothReturnedSoTheAdvisorIsAsked() {
        List<Person> found = CustomerMemoryService.find(people(debrief("a", "Nick Tan", 1), debrief("b", "Nick Lee", 9)), "Nick");
        assertEquals(2, found.size());
        assertEquals("Nick Tan", found.get(0).name(), "the more recent one is listed first");
    }

    @Test
    void theFullNameSingleOutsOnePerson() {
        List<Person> found = CustomerMemoryService.find(people(debrief("a", "Nick Tan", 1), debrief("b", "Nick Lee", 9)), "Nick Lee");
        assertEquals(1, found.size());
        assertEquals("Nick Lee", found.get(0).name());
    }

    @Test
    void nicknamesAndSmallMisspellingsStillMatch() {
        List<Person> ps = people(debrief("a", "Nicholas Wong", 1));
        assertEquals(1, CustomerMemoryService.find(ps, "Nick").size());
        assertEquals(1, CustomerMemoryService.find(ps, "Nicolas").size());
        assertEquals(1, CustomerMemoryService.find(people(debrief("a", "Priya Nair", 1)), "Pria").size());
    }

    @Test
    void honorificsAndFillerWordsAreIgnored() {
        assertEquals(1, CustomerMemoryService.find(people(debrief("a", "Nick Tan", 1)), "Mr Tan").size());
        assertEquals(1, CustomerMemoryService.find(people(debrief("a", "Nick Tan", 1)), "the customer Nick").size());
    }

    @Test
    void aNameNobodyHasMatchesNobody() {
        assertTrue(CustomerMemoryService.find(people(debrief("a", "Nick Tan", 1), debrief("b", "Priya Nair", 2)), "Zachary").isEmpty());
        assertTrue(CustomerMemoryService.find(people(debrief("a", "Nick Tan", 1)), "  ").isEmpty());
    }

    @Test
    void soundexGroupsNamesThatSoundAlike() {
        assertEquals(CustomerMemoryService.soundex("smith"), CustomerMemoryService.soundex("smyth"));
        assertNotEquals(CustomerMemoryService.soundex("smith"), CustomerMemoryService.soundex("jones"));
    }

    // ── what changed ──

    @Test
    void noChangeIsSaidPlainly() {
        CustomerProfile a = debrief("a", "Nick", 9);
        CustomerProfile b = debrief("b", "Nick", 1);
        a.setAge(40);
        b.setAge(40);
        assertTrue(CustomerMemoryService.changes(a, b).isEmpty());
    }

    @Test
    void newDependantsPoliciesAndConcernsAreReportedAndOldOnesAreNot() {
        CustomerProfile a = debrief("a", "Nick", 9);
        CustomerProfile b = debrief("b", "Nick", 1);
        a.setDependantsList(List.of("wife"));
        b.setDependantsList(List.of("Wife", "son Ethan (8)"));
        a.setExistingPolicies(List.of("AIA term life"));
        b.setExistingPolicies(List.of("AIA term life", "MediShield"));
        a.setGoalsAndConcerns(List.of("retire at 60"));
        b.setGoalsAndConcerns(List.of("retire at 60", "worried about hospital bills"));
        String all = String.join(" ", CustomerMemoryService.changes(a, b));
        assertTrue(all.contains("son Ethan (8)"));
        assertFalse(all.toLowerCase().contains("new dependants: wife"));
        assertTrue(all.contains("MediShield"));
        assertTrue(all.contains("worried about hospital bills"));
        assertFalse(all.contains("retire at 60"));
    }

    @Test
    void anAgeOrBudgetChangeIsReported() {
        CustomerProfile a = debrief("a", "Nick", 9);
        CustomerProfile b = debrief("b", "Nick", 1);
        a.setAge(40);
        b.setAge(41);
        a.setBudgetNotes("around 300 a month");
        b.setBudgetNotes("up to 500 a month");
        String all = String.join(" ", CustomerMemoryService.changes(a, b));
        assertTrue(all.contains("41"));
        assertTrue(all.contains("up to 500 a month"));
    }

    // ── the lead-in ──

    @Test
    void theLeadInDoesNotRepeatTheDayWhenBothDebriefsWereOnIt() {
        List<Person> ps = people(debrief("a", "Nick Tan", 1), debrief("b", "Nick Tan", 1));
        ps.get(0).debriefs().get(1).setAge(40);
        ps.get(0).debriefs().get(0).setAge(41);
        String lead = CustomerMemoryService.lead(ps.get(0), NOW);
        assertTrue(lead.startsWith("You last debriefed Nick Tan yesterday."), lead);
        assertTrue(lead.contains("Compared with the debrief before that,"), lead);
        assertFalse(lead.contains("your debrief yesterday"), lead);
    }

    @Test
    void theLeadInDatesTheEarlierDebriefWhenItWasAnotherDay() {
        List<Person> ps = people(debrief("a", "Nick Tan", 1), debrief("b", "Nick Tan", 20));
        String lead = CustomerMemoryService.lead(ps.get(0), NOW);
        assertTrue(lead.contains("Compared with your debrief on 14 October, nothing in the facts has changed."), lead);
    }

    @Test
    void aSingleDebriefHasNoComparison() {
        assertEquals("You last debriefed Nick Tan on Friday.", CustomerMemoryService.lead(people(debrief("a", "Nick Tan", 4)).get(0), NOW));
    }

    // ── wording ──

    @Test
    void whenIsSpokenTheWayAPersonWouldSayIt() {
        assertEquals("today", CustomerMemoryService.spokenWhen(NOW.minus(2, ChronoUnit.HOURS), NOW));
        assertEquals("yesterday", CustomerMemoryService.spokenWhen(NOW.minus(1, ChronoUnit.DAYS), NOW));
        assertEquals("on Friday", CustomerMemoryService.spokenWhen(NOW.minus(4, ChronoUnit.DAYS), NOW));
        assertEquals("on 18 October", CustomerMemoryService.spokenWhen(NOW.minus(16, ChronoUnit.DAYS), NOW));
        assertEquals("on 3 November 2025", CustomerMemoryService.spokenWhen(NOW.minus(365, ChronoUnit.DAYS), NOW));
    }

    @Test
    void thePlainSummaryNeverInventsAnything() {
        CustomerProfile p = debrief("a", "Nick Tan", 1);
        p.setAge(42);
        p.setOccupation("teacher");
        p.setGoalsAndConcerns(List.of("children's education"));
        String s = CustomerMemoryService.plainSummary(p, "Nick");
        assertTrue(s.startsWith("Here's your briefing on Nick."));
        assertTrue(s.contains("42") && s.contains("teacher") && s.contains("children's education"));
        assertTrue(s.contains("Suggestions have not been prepared yet"));
    }
}

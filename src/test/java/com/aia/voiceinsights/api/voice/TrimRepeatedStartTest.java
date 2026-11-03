package com.aia.voiceinsights.api.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrimRepeatedStartTest {

    @Test
    void removesAnExactRepeatOfTheLastWords() {
        assertEquals("she has a daughter aged eight",
                AzureOpenAiRealtimeTranscriptionClient.trimRepeatedStart("I met Mrs Lim today. She is 42 and works as a school teacher.",
                        "school teacher, she has a daughter aged eight"));
    }

    @Test
    void removesARepeatThatTheModelPrefixedWithWordsOfItsOwn() {
        // Seen live: the second segment began "She is a school teacher," although the first already ended "...as a school teacher."
        assertEquals("she has a daughter aged eight, she is worried about retirement",
                AzureOpenAiRealtimeTranscriptionClient.trimRepeatedStart("I met Mrs Lim today. She is 42 and works as a school teacher.",
                        "She is a school teacher, she has a daughter aged eight, she is worried about retirement"));
    }

    @Test
    void keepsTextThatOnlyLooksSimilar() {
        String cur = "She is a teacher and she has a daughter";
        assertEquals(cur, AzureOpenAiRealtimeTranscriptionClient.trimRepeatedStart("He works as a driver and likes football", cur));
    }

    @Test
    void doesNotCutOnASingleShortWord() {
        String cur = "and the budget is three hundred dollars";
        assertEquals(cur, AzureOpenAiRealtimeTranscriptionClient.trimRepeatedStart("She is worried about the loan and", cur));
    }

    @Test
    void handlesThaiRepeatsEvenThoughThaiHasNoSpaces() {
        // seen live: "...ชื่อสมชาย" then a segment starting "ชาย อายุ..." showed the name twice
        assertEquals("อายุสี่สิบสองปี ทำงานเป็นวิศวกร",
                AzureOpenAiRealtimeTranscriptionClient.trimRepeatedStart("วันนี้ผมได้พบลูกค้าชื่อสมชาย", "ลูกค้าชื่อสมชาย อายุสี่สิบสองปี ทำงานเป็นวิศวกร"));
    }

    @Test
    void doesNotCutThaiTextOnAShortCoincidence() {
        String cur = "ครับ ผมคิดว่าเรื่องนี้สำคัญ";
        assertEquals(cur, AzureOpenAiRealtimeTranscriptionClient.trimRepeatedStart("ขอบคุณมากครับ", cur));
    }

    @Test
    void handlesChineseRepeats() {
        assertEquals("她很担心退休", AzureOpenAiRealtimeTranscriptionClient.trimRepeatedStart("她今年四十二岁，是一名老师", "是一名老师，她很担心退休").replaceFirst("^，", ""));
    }

    @Test
    void aThaiNumberWrittenInDigitsThenInWordsIsOneRepeat() {
        // Seen live: one segment ended "อายุ 42 ปี" and the next began "สองปี เป็นวิศวกร" (the same audio, the number as words).
        assertEquals("เป็นวิศวกร", AzureOpenAiRealtimeTranscriptionClient.trimRepeatedStart("พบลูกค้าชื่อ สมชาย อายุ 42 ปี", "สองปี เป็นวิศวกร"));
        // ...and "ภรรยาชื่อสุดา มีลูกชายอายุ 8 ขวบ" followed by "ลูกแปดขวบ เขากังวล".
        assertEquals("เขากังวล", AzureOpenAiRealtimeTranscriptionClient.trimRepeatedStart("แต่งงานแล้ว มีลูกชายอายุ 8 ขวบ", "ลูกแปดขวบ เขากังวล"));
    }

    @Test
    void thaiNumbersAreReadTheWayThaiSaysThem() {
        assertEquals("สี่สิบสอง", AzureOpenAiRealtimeTranscriptionClient.thaiNumbersAsWords("42"));
        assertEquals("ยี่สิบเอ็ด", AzureOpenAiRealtimeTranscriptionClient.thaiNumbersAsWords("21"));
        assertEquals("สิบเอ็ด", AzureOpenAiRealtimeTranscriptionClient.thaiNumbersAsWords("11"));
        assertEquals("แปด", AzureOpenAiRealtimeTranscriptionClient.thaiNumbersAsWords("8"));
        assertEquals("หนึ่งแสนสองหมื่น", AzureOpenAiRealtimeTranscriptionClient.thaiNumbersAsWords("120,000"));
        assertEquals("สี่ล้าน", AzureOpenAiRealtimeTranscriptionClient.thaiNumbersAsWords("4000000"));
        assertEquals("อายุ ห้าสิบ ปี", AzureOpenAiRealtimeTranscriptionClient.thaiNumbersAsWords("อายุ 50 ปี"));
    }

    @Test
    void aShortThaiMatchIsOnlyTrustedWhenItSitsOnANumber() {
        String cur = "ปีใหม่ ครอบครัวมีความสุข";
        assertEquals(cur, AzureOpenAiRealtimeTranscriptionClient.trimRepeatedStart("เขาทำงานมาหลายปี", cur));
    }
}

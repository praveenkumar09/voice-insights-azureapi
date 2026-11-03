package com.aia.voiceinsights.api.service;

import java.util.List;
import java.util.Map;

/**
 * Everything Spark says that is fixed (the greeting, the consent questions, goodbyes, the "Mm-hm"s), in each language it
 * speaks. One place, so the server and the browser always agree. English is the default.
 *
 * NOTE for review: the Mandarin, Malay, Tamil, Thai, Filipino and Vietnamese wording should be checked by native speakers (and by
 * Compliance for the consent and disclosure wording) before real customers hear it. The Thai, Filipino and Vietnamese sets were
 * written for the proof of concept and checked by back-translation (see JunoPhrasesTest for the completeness checks).
 */
public final class JunoPhrases {

    private JunoPhrases() {}

    public record Phrases(String language, String greeting, String declined, String unclear, String firstQuestion, String closing,
                          String wrap, String askAway, String tellMore, String noFigures, String nudge, String missed,
                          String anythingElse, String yes, String no, String yesText, String noText, List<String> acks) {}

    public static final String DEFAULT = "en";

    private static final Map<String, Phrases> ALL = Map.of(
            "en", new Phrases("English",
                    "Hello, I'm Spark, your AIA Singapore digital suggestion assistant. Before we begin, with your permission I'd like to record and analyse our conversation, so your advisor can help you better. Is that all right?",
                    "Of course, that's completely fine. I won't keep anything from this chat. Your advisor will be happy to take it from here.",
                    "Sorry, I didn't quite catch that. Is it all right if I record and analyse our chat, so your advisor can help you better? A simple yes or no is fine.",
                    "Thank you. To start, could I have your name?",
                    "That's really helpful, thank you. Before I hand you over to your advisor, is there anything you'd like to ask me?",
                    "Thank you so much for sharing all of that. Your advisor will review everything and take it from here.",
                    "Of course. What would you like to ask?",
                    "Thank you. Could you tell me a little more about that?",
                    "I can't give figures like that, as they depend on your situation. Your advisor will go through the details with you.",
                    "Take your time. I'm listening.",
                    "Sorry, I missed that. Could you say it once more?",
                    "Is there anything else you'd like to ask?",
                    "Yes, that's fine", "No thanks", "Yes, that is fine.", "No, I would rather not.",
                    List.of("Mm, I see.", "Okay, got it.", "Right, thank you.", "I see, thanks.", "Alright, got it.")),
            "zh", new Phrases("Mandarin Chinese (Simplified)",
                    "您好，我是 Spark，AIA 新加坡的数字建议助手。在开始之前，如果您同意，我想录下并分析我们的对话，让您的顾问能更好地帮到您。可以吗？",
                    "没问题，完全可以。这次谈话我不会保留任何内容。您的顾问会很乐意接手。",
                    "抱歉，我没听清楚。请问我可以录下并分析我们的对话，让您的顾问更好地帮助您吗？回答“可以”或“不可以”就行。",
                    "谢谢您。首先，请问您怎么称呼？",
                    "非常感谢，这些信息很有帮助。在把您交给顾问之前，您还有什么想问我的吗？",
                    "非常感谢您分享这些。您的顾问会查看所有内容，并接手后续的事宜。",
                    "当然可以，请问您想问什么？",
                    "谢谢。能再多说一点吗？",
                    "我无法提供这类数字，因为它们取决于您的具体情况。您的顾问会和您详细说明。",
                    "不着急，我在听。",
                    "抱歉，我没听清楚。您可以再说一遍吗？",
                    "还有其他想问的吗？",
                    "好的，可以", "不用了", "好的，可以。", "不用了，我不想被录音。",
                    List.of("嗯，我明白。", "好的，明白了。", "好的，谢谢。")),
            "ms", new Phrases("Malay (Bahasa Melayu)",
                    "Helo, saya Spark, pembantu digital dan cadangan AIA Singapura. Sebelum kita mulakan, dengan kebenaran anda, saya ingin merakam dan menganalisis perbualan kita supaya penasihat anda dapat membantu anda dengan lebih baik. Adakah itu tidak mengapa?",
                    "Baiklah, tidak mengapa langsung. Saya tidak akan menyimpan apa-apa daripada perbualan ini. Penasihat anda akan dengan senang hati meneruskannya.",
                    "Maaf, saya kurang jelas. Bolehkah saya merakam dan menganalisis perbualan kita supaya penasihat anda dapat membantu anda dengan lebih baik? Jawapan ya atau tidak sudah memadai.",
                    "Terima kasih. Untuk bermula, boleh saya tahu nama anda?",
                    "Terima kasih, ini sangat membantu. Sebelum saya serahkan anda kepada penasihat anda, adakah anda ingin bertanya apa-apa kepada saya?",
                    "Terima kasih banyak kerana berkongsi semua itu. Penasihat anda akan menyemak semuanya dan meneruskan dari sini.",
                    "Sudah tentu. Apa yang ingin anda tanyakan?",
                    "Terima kasih. Boleh ceritakan sedikit lagi?",
                    "Saya tidak dapat memberikan angka seperti itu kerana ia bergantung pada keadaan anda. Penasihat anda akan menerangkannya dengan lanjut.",
                    "Ambil masa anda. Saya sedang mendengar.",
                    "Maaf, saya terlepas. Boleh anda ulang sekali lagi?",
                    "Ada apa-apa lagi yang ingin anda tanya?",
                    "Ya, boleh", "Tidak, terima kasih", "Ya, boleh.", "Tidak, saya tidak mahu dirakam.",
                    List.of("Mm, saya faham.", "Baik, faham.", "Okey, terima kasih.")),
            "ta", new Phrases("Tamil",
                    "வணக்கம், நான் ஸ்பார்க், ஏஐஏ சிங்கப்பூரின் டிஜிட்டல் யோசனை உதவியாளர். தொடங்குவதற்கு முன், உங்கள் அனுமதியுடன், உங்கள் ஆலோசகர் உங்களுக்கு நன்றாக உதவ, நமது உரையாடலைப் பதிவு செய்து பகுப்பாய்வு செய்ய விரும்புகிறேன். சரியா?",
                    "நிச்சயமாக, பரவாயில்லை. இந்த உரையாடலில் எதையும் நான் வைத்துக்கொள்ள மாட்டேன். உங்கள் ஆலோசகர் மகிழ்ச்சியுடன் தொடர்வார்.",
                    "மன்னிக்கவும், எனக்குச் சரியாகக் கேட்கவில்லை. உங்கள் ஆலோசகர் உங்களுக்கு நன்றாக உதவ, நமது உரையாடலைப் பதிவு செய்து பகுப்பாய்வு செய்யலாமா? 'ஆம்' அல்லது 'இல்லை' என்று சொன்னால் போதும்.",
                    "நன்றி. தொடங்குவதற்கு, உங்கள் பெயரைச் சொல்ல முடியுமா?",
                    "மிக்க நன்றி, இது மிகவும் உதவியாக உள்ளது. உங்களை ஆலோசகரிடம் ஒப்படைப்பதற்கு முன், என்னிடம் ஏதாவது கேட்க விரும்புகிறீர்களா?",
                    "எல்லாவற்றையும் பகிர்ந்ததற்கு மிக்க நன்றி. உங்கள் ஆலோசகர் அனைத்தையும் பார்த்து, இங்கிருந்து தொடர்வார்.",
                    "நிச்சயமாக. நீங்கள் என்ன கேட்க விரும்புகிறீர்கள்?",
                    "நன்றி. இதைப் பற்றி இன்னும் கொஞ்சம் சொல்ல முடியுமா?",
                    "அத்தகைய எண்களை என்னால் தர முடியாது, ஏனெனில் அவை உங்கள் நிலைமையைப் பொறுத்தவை. உங்கள் ஆலோசகர் விவரமாக விளக்குவார்.",
                    "அவசரம் இல்லை. நான் கேட்டுக்கொண்டிருக்கிறேன்.",
                    "மன்னிக்கவும், எனக்குப் புரியவில்லை. மீண்டும் ஒருமுறை சொல்ல முடியுமா?",
                    "வேறு ஏதாவது கேட்க வேண்டுமா?",
                    "சரி, பரவாயில்லை", "வேண்டாம்", "சரி, பரவாயில்லை.", "வேண்டாம், பதிவு செய்ய வேண்டாம்.",
                    List.of("ம்ம், புரிகிறது.", "சரி, புரிந்தது.", "சரி, நன்றி.")),
            "th", new Phrases("Thai",
                    "สวัสดีค่ะ ดิฉันชื่อ Spark เป็นผู้ช่วยดิจิทัลของ AIA สิงคโปร์ที่ให้ข้อเสนอแนะ ก่อนเริ่มสนทนา หากคุณอนุญาต ดิฉันขออัดเสียงและวิเคราะห์บทสนทนาของเรา เพื่อให้ที่ปรึกษาของคุณช่วยเหลือคุณได้ดียิ่งขึ้น ได้ไหมคะ",
                    "ได้เลยค่ะ ไม่เป็นไรเลย ดิฉันจะไม่เก็บอะไรจากบทสนทนานี้ไว้ ที่ปรึกษาของคุณยินดีดูแลต่อจากนี้ค่ะ",
                    "ขอโทษค่ะ ดิฉันฟังไม่ค่อยชัด ขออัดเสียงและวิเคราะห์บทสนทนาของเรา เพื่อให้ที่ปรึกษาของคุณช่วยเหลือคุณได้ดียิ่งขึ้น ได้ไหมคะ ตอบว่า “ได้” หรือ “ไม่ได้” ก็พอค่ะ",
                    "ขอบคุณค่ะ เริ่มต้นนี้ ขอทราบชื่อของคุณได้ไหมคะ",
                    "ขอบคุณค่ะ ข้อมูลนี้เป็นประโยชน์มากเลย ก่อนที่ดิฉันจะส่งต่อให้ที่ปรึกษาของคุณ มีอะไรที่อยากถามดิฉันไหมคะ",
                    "ขอบคุณมากค่ะที่แบ่งปันเรื่องราวทั้งหมดนี้ ที่ปรึกษาของคุณจะตรวจสอบทุกอย่างและดูแลต่อจากนี้ค่ะ",
                    "ได้เลยค่ะ อยากถามอะไรคะ",
                    "ขอบคุณค่ะ ช่วยเล่าเรื่องนี้เพิ่มเติมอีกนิดได้ไหมคะ",
                    "ดิฉันไม่สามารถให้ตัวเลขแบบนี้ได้ค่ะ เพราะขึ้นอยู่กับสถานการณ์ของคุณ ที่ปรึกษาของคุณจะอธิบายรายละเอียดให้ฟังค่ะ",
                    "ไม่ต้องรีบนะคะ ดิฉันกำลังฟังอยู่ค่ะ",
                    "ขอโทษค่ะ ดิฉันฟังไม่ทัน รบกวนพูดอีกครั้งได้ไหมคะ",
                    "มีอะไรอยากถามเพิ่มเติมอีกไหมคะ",
                    "ตกลง", "ไม่ ขอบคุณ", "ตกลง ไม่มีปัญหา", "ไม่ ขอไม่ให้บันทึกเสียงจะดีกว่า",
                    List.of("อืม เข้าใจแล้วค่ะ", "ค่ะ รับทราบค่ะ", "ค่ะ ขอบคุณค่ะ", "เข้าใจแล้วค่ะ ขอบคุณนะคะ", "ได้ค่ะ รับทราบ")),
            "fil", new Phrases("Filipino (Tagalog)",
                    "Kumusta po! Ako si Spark, ang digital na katulong ng AIA Singapore sa pagbibigay ng mga mungkahi. Bago tayo magsimula, kung papayag po kayo, nais kong i-record at suriin ang ating usapan para mas matulungan kayo ng inyong advisor. Okay lang po ba iyon?",
                    "Walang problema po, ayos lang po iyon. Wala po akong itatabi mula sa usapang ito. Ipagpapatuloy na po ito ng inyong advisor.",
                    "Pasensya na po, hindi ko po masyadong narinig. Okay lang po ba na i-record at suriin ko ang usapan natin para mas matulungan kayo ng inyong advisor? Sapat na po ang oo o hindi.",
                    "Salamat po. Para makapagsimula, maaari ko po bang malaman ang pangalan ninyo?",
                    "Malaking tulong po iyon, salamat. Bago ko kayo ipasa sa inyong advisor, may gusto po ba kayong itanong sa akin?",
                    "Maraming salamat po sa pagbabahagi ng lahat ng iyon. Susuriin po ng inyong advisor ang lahat at siya na po ang magpapatuloy.",
                    "Siyempre po. Ano po ang gusto ninyong itanong?",
                    "Salamat po. Maaari po ba ninyong ikuwento nang kaunti pa ang tungkol diyan?",
                    "Hindi po ako makapagbibigay ng ganyang mga numero dahil nakadepende po iyon sa inyong sitwasyon. Ipapaliwanag po ito nang detalyado ng inyong advisor.",
                    "Dahan-dahan lang po. Nakikinig po ako.",
                    "Pasensya na po, hindi ko po nasundan. Pwede po bang ulitin ninyo?",
                    "May iba pa po ba kayong gustong itanong?",
                    "Opo, okay lang po", "Hindi, salamat po", "Opo, okay lang po iyon.", "Hindi po, mas gusto ko pong hindi.",
                    List.of("Mm, naiintindihan ko po.", "Sige po, nakuha ko.", "Opo, salamat po.", "Ah, ganoon po. Salamat.", "Sige po, naintindihan ko.")),
            "vi", new Phrases("Vietnamese",
                    "Xin chào anh/chị, tôi là Spark, trợ lý số của AIA Singapore giúp đưa ra các gợi ý. Trước khi bắt đầu, nếu anh/chị đồng ý, tôi muốn ghi âm và phân tích cuộc trò chuyện của chúng ta để tư vấn viên của anh/chị có thể hỗ trợ tốt hơn. Anh/chị có đồng ý không ạ?",
                    "Dạ, hoàn toàn được ạ. Tôi sẽ không lưu giữ bất cứ điều gì từ cuộc trò chuyện này. Tư vấn viên của anh/chị sẽ tiếp tục hỗ trợ từ đây.",
                    "Xin lỗi, tôi nghe chưa rõ. Tôi có thể ghi âm và phân tích cuộc trò chuyện của chúng ta để tư vấn viên của anh/chị hỗ trợ tốt hơn được không ạ? Anh/chị chỉ cần nói “được” hoặc “không”.",
                    "Cảm ơn anh/chị. Để bắt đầu, anh/chị cho tôi xin tên được không ạ?",
                    "Những thông tin này rất hữu ích, cảm ơn anh/chị. Trước khi chuyển anh/chị cho tư vấn viên, anh/chị có muốn hỏi tôi điều gì không ạ?",
                    "Cảm ơn anh/chị rất nhiều vì đã chia sẻ. Tư vấn viên của anh/chị sẽ xem lại mọi thông tin và tiếp tục từ đây.",
                    "Dạ, được ạ. Anh/chị muốn hỏi điều gì?",
                    "Cảm ơn anh/chị. Anh/chị có thể nói rõ hơn một chút về điều đó được không ạ?",
                    "Tôi không thể đưa ra những con số như vậy, vì chúng phụ thuộc vào hoàn cảnh của anh/chị. Tư vấn viên sẽ cùng anh/chị đi vào chi tiết.",
                    "Anh/chị cứ từ từ. Tôi đang lắng nghe.",
                    "Xin lỗi, tôi chưa nghe kịp. Anh/chị có thể nói lại một lần nữa được không ạ?",
                    "Anh/chị còn muốn hỏi điều gì nữa không ạ?",
                    "Vâng, được ạ", "Không, cảm ơn", "Vâng, được ạ.", "Không, tôi không muốn ghi âm.",
                    List.of("Vâng, tôi hiểu.", "Dạ, tôi đã ghi nhận.", "Vâng, cảm ơn anh/chị.", "Tôi hiểu rồi, cảm ơn.", "Dạ vâng, tôi hiểu rồi.")));

    /** Only the languages an admin has turned on are accepted; anything else is the fallback language (see {@link Languages#normalize}). */
    public static String normalize(String lang) {
        String c = Languages.normalize(lang);
        return ALL.containsKey(c) ? c : DEFAULT;
    }

    public static java.util.Set<String> codes() { return ALL.keySet(); }

    /** The lines for a language, naming the selected business unit where they name AIA's Singapore business. */
    public static Phrases of(String lang) {
        Phrases p = ALL.get(normalize(lang));
        if ("sg".equals(BusinessUnits.current().id())) return p;
        java.util.function.UnaryOperator<String> f = BusinessUnits::localize;
        return new Phrases(p.language(), f.apply(p.greeting()), f.apply(p.declined()), f.apply(p.unclear()), f.apply(p.firstQuestion()), f.apply(p.closing()),
                f.apply(p.wrap()), f.apply(p.askAway()), f.apply(p.tellMore()), f.apply(p.noFigures()), f.apply(p.nudge()), f.apply(p.missed()),
                f.apply(p.anythingElse()), p.yes(), p.no(), p.yesText(), p.noText(), p.acks());
    }
}

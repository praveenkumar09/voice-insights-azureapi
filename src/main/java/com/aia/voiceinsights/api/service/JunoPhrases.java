package com.aia.voiceinsights.api.service;

import java.util.List;
import java.util.Map;

/**
 * Everything Juno says that is fixed (the greeting, the consent questions, goodbyes, the "Mm-hm"s), in each language it
 * speaks. One place, so the server and the browser always agree. English is the default.
 *
 * NOTE for review: the Mandarin, Malay and Tamil wording should be checked by native speakers (and by Compliance for the
 * consent and disclosure wording) before real customers hear it.
 */
public final class JunoPhrases {

    private JunoPhrases() {}

    public record Phrases(String language, String greeting, String declined, String unclear, String firstQuestion, String closing,
                          String wrap, String askAway, String tellMore, String noFigures, String nudge, String missed,
                          String anythingElse, String yes, String no, String yesText, String noText, List<String> acks) {}

    public static final String DEFAULT = "en";

    private static final Map<String, Phrases> ALL = Map.of(
            "en", new Phrases("English",
                    "Hello, I'm Juno, your AIA Singapore digital and recommendation assistant. Before we begin, with your permission I'd like to record and analyse our conversation, so your advisor can help you better. Is that all right?",
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
                    "您好，我是 Juno，AIA 新加坡的数字与推荐助手。在开始之前，如果您同意，我想录下并分析我们的对话，让您的顾问能更好地帮到您。可以吗？",
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
                    "Helo, saya Juno, pembantu digital dan cadangan AIA Singapura. Sebelum kita mulakan, dengan kebenaran anda, saya ingin merakam dan menganalisis perbualan kita supaya penasihat anda dapat membantu anda dengan lebih baik. Adakah itu tidak mengapa?",
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
                    "வணக்கம், நான் ஜூனோ, ஏஐஏ சிங்கப்பூரின் டிஜிட்டல் மற்றும் பரிந்துரை உதவியாளர். தொடங்குவதற்கு முன், உங்கள் அனுமதியுடன், உங்கள் ஆலோசகர் உங்களுக்கு நன்றாக உதவ, நமது உரையாடலைப் பதிவு செய்து பகுப்பாய்வு செய்ய விரும்புகிறேன். சரியா?",
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
                    List.of("ம்ம், புரிகிறது.", "சரி, புரிந்தது.", "சரி, நன்றி.")));

    /** Only the four supported languages are accepted; anything else is English. */
    public static String normalize(String lang) {
        return lang != null && ALL.containsKey(lang.toLowerCase()) ? lang.toLowerCase() : DEFAULT;
    }

    public static Phrases of(String lang) {
        return ALL.get(normalize(lang));
    }
}

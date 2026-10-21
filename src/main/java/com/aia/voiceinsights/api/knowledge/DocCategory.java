package com.aia.voiceinsights.api.knowledge;

/**
 * The document categories a product folder may contain. The folder name (not the enum name) is what is matched inside an uploaded
 * zip, and what is stored as {@code doc_category}. Same set as voice-insights-ingestor, so existing rows and new uploads line up.
 */
public enum DocCategory {
    RIDER("Rider"),
    OTHERS("Others"),
    PRODUCT_SUMMARY("Product Summary"),
    CONTRACT("Contract"),
    FACT_SHEET("Fact Sheet"),
    FAQ("FAQ");

    private final String folderName;

    DocCategory(String folderName) { this.folderName = folderName; }

    public String folderName() { return folderName; }

    /**
     * Case-, whitespace- and punctuation-insensitive match of a folder name, tolerating singular/plural and the common spellings
     * ("Other", "Riders", "Contracts", "FAQs", "FactSheet", "Product Summaries"); null if it is not a category.
     */
    public static DocCategory fromFolderName(String folderName) {
        String n = folderName.toLowerCase().replaceAll("[^a-z]", "");
        for (DocCategory c : values()) {
            String f = c.folderName.toLowerCase().replaceAll("[^a-z]", "");
            if (f.equals(n) || (f + "s").equals(n) || f.equals(n + "s") || (f.endsWith("y") && (f.substring(0, f.length() - 1) + "ies").equals(n))) return c;
        }
        return null;
    }
}

package com.ai.aiagent.chat;

public record BotProfile(Long id, String slug, String personaPrompt, String provider,
                         String model) {

    private static final BotProfile NONE = new BotProfile(null, null, null, null, null);

    public static BotProfile none() {
        return NONE;
    }

    // Never null: an empty bot tag breaks the Prometheus series. Web traffic is "web".
    public String label() {
        return slug == null || slug.isBlank() ? "web" : slug;
    }

    public boolean hasPersona() {
        return personaPrompt != null && !personaPrompt.isBlank();
    }
}

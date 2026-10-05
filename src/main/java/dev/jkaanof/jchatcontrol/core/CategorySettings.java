package dev.jkaanof.jchatcontrol.core;

/**
 * Settings of one moderation category (profanity, racism, religion, language, politics ...).
 *
 * @param id            category id used in word lists, patterns and AI answers
 * @param enabled       disabled categories are ignored everywhere (also not sent to the AI prompt)
 * @param displayName   name shown to players / staff
 * @param action        what to do with violating messages
 * @param points        violation points added to the player
 * @param aiDescription description given to the AI so it knows what to flag
 */
public record CategorySettings(String id, boolean enabled, String displayName, Action action, int points,
                               String aiDescription) {
}

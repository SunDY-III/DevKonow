package com.devknow.mentor;

import lombok.Data;

/** 成就/勋章。 */
@Data
public class MentorAchievement {
    private String id;
    private String title;
    private String description;
    private String icon;       // star / compass / trophy
    private String milestoneId;
    private String unlockedAt;
}

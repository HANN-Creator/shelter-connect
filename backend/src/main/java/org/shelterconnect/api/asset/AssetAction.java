package org.shelterconnect.api.asset;

public enum AssetAction {
    BASE(0, false, ""),
    IDLE(140, true, "Stand in place with subtle breathing, one gentle blink and a small head glance. All paws stay planted. Seamless loop."),
    WALK(75, true, "Walk in place with a natural alternating four-legged canine gait. No sideways travel. Seamless loop."),
    RUN(55, true, "Run in place with a coordinated four-legged canine gallop, flexing front and hind legs. No sideways travel. Seamless loop."),
    SNIFF(100, true, "Lower the head to sniff the ground near the front paws, then return to the starting pose. Paws stay planted. Seamless loop."),
    TAIL_WAG(90, true, "Gently wag only the tail side to side. All four paws planted, body and head stable. Subtle seamless loop."),
    BACK_OFF(90, true, "Take small cautious backward steps in place, still looking right. Alternate paws without translating across the canvas. Seamless backward walk loop."),
    SIT(90, false, "Bend the hind legs and lower the hips into a natural seated dog pose. Front paws planted. End seated and hold for the last two frames. Do not stand again."),
    LIE_DOWN(100, false, "Bend front and hind legs and lower chest and belly to rest on the ground. End lying down and hold for the last two frames. Do not stand again.");

    public static final String VERSION = "pixellab-harness-v3";
    final int durationMs;
    final boolean loop;
    private final String motion;
    AssetAction(int durationMs, boolean loop, String motion) { this.durationMs=durationMs; this.loop=loop; this.motion=motion; }
    String prompt() {
        return motion + " Same puppy identity as the first frame, four legs, same muzzle, ears, coat markings and palette."
            + " Facing screen right in three-quarter view. Locked camera and scale, shared ground line."
            + " Transparent pixel art. No extra limbs, props, text, turning or zoom.";
    }
    static final String BASE_PROMPT = "Create one full-body dog sprite for a cozy pixel-art shelter game. The PHOTO reference defines the dog's visual identity. "
        + "Preserve coat colors, markings, ears, muzzle and tail. Ignore toys, other animals, people, room, pose and text in the photo. "
        + "Standing naturally on FOUR planted paws, closed mouth, slightly elevated three-quarter view facing screen RIGHT. Head to the right, tail to the left. "
        + "Cute readable proportions with a modestly enlarged head, compact but natural dog anatomy. "
        + "The separate tan-dog STYLE reference is ONLY for crisp pixel clusters, outline thickness and game-art detail; NEVER copy its colors, floppy ears or muzzle markings. "
        + "Native 64x64 pixel art, restrained palette derived from the subject photo, warm gray-brown outline, no anti-aliasing. "
        + "Full silhouette within 6px side margins and 4px top margin; no cropped ears, tail or paws. Transparent background. "
        + "No scenery, floor, cast shadow, clothing, collar, text, symbols, props, extra legs, duplicate dogs or personality claims.";
}

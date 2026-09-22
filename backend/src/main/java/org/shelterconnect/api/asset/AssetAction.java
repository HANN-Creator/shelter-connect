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

    public static final String VERSION = "pixellab-pro64-v2";
    final int durationMs;
    final boolean loop;
    private final String motion;
    AssetAction(int durationMs, boolean loop, String motion) { this.durationMs=durationMs; this.loop=loop; this.motion=motion; }
    String prompt() {
        return motion + " Same puppy identity as the first frame, four legs, same muzzle, ears, coat markings and palette."
            + " Facing screen right in three-quarter view. Locked camera and scale, shared ground line."
            + " Transparent pixel art. No extra limbs, props, text, turning or zoom.";
    }
    static final String BASE_PROMPT = "Create a complete 64x64 pixel-art dog sprite using only the dog's visual identity from the reference photo. "
        + "Preserve coat colors, markings, ears, muzzle and tail. Ignore toys, other animals, people, room, pose and text in the photo. "
        + "One quadruped dog standing naturally, facing screen right in a three-quarter view, all paws and tail visible. "
        + "Crisp restrained pixel clusters, consistent dark outline, no anti-aliasing. Centered with at least 4 pixels of transparent padding. "
        + "No background, props, text or personality claims.";
}

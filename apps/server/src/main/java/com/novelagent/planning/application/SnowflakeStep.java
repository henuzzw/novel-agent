package com.novelagent.planning.application;

/** Nine progressive prose steps, not nine independently configured model agents. */
public enum SnowflakeStep {
    CORE("一句话故事核心", "用25至100字的一句话概括：主角遭遇核心事件，必须采取关键行动，否则承担灾难后果，同时隐藏危机正在发酵。体现显性冲突、核心驱动力及世界关键矛盾。只返回单句。", 1000),
    SYNOPSIS("一段故事梗概", "将故事核心扩为一段梗概，完整交代开端、关键转折与结局。变化由人物选择和后果引发，不提前扩成逐章大纲。", 3000),
    CHARACTER_ARCS("主要角色与弧线", "设计主要角色的具体姓名、目标、动机、冲突、顿悟与人物弧线；区分表面追求、情感渴望与深层需求。保留已有人名及作者匿名要求。", 8000),
    PLOT_SUMMARY("数页情节概要", "将一段梗概扩为约一页到数页的情节概要，展开完整因果链、错误选择、转折、胜利、代价和结局。按目标篇幅分配，不强迫短篇换地图或升级。", 10000),
    CHARACTER_BIOGRAPHIES("人物经历与各自故事线", "深化主要角色经历及各自故事线，写清经历怎样形成应对方式，角色在主线之外想得到什么，以及各自选择如何影响主线。续写不补造既往经历。", 10000),
    DETAILED_OUTLINE("详细故事发展", "依据前置情节概要扩为详细大纲，完整展开目标、行动、阻碍、选择、结果及新局面；按篇幅列出章节及关键因果链，伏笔与回报有来源和落点。", 16000),
    CHARACTER_SETTINGS("完整人物设定", "整合并完善背景、性格、价值观、能力边界、初始状态、关系、秘密及变化；兼容已设计的弧线和具体剧情，不重新推翻前置人物。作者侧秘密与角色知情分开。", 12000),
    SCENE_LIST("全部场景清单", "按章节列出所有场景，写清视角、时间地点、参与者、目标、阻力、行动、转折、结果及推进作用。场景数量服从实际篇幅，不把开端发展结局当作三个场景。", 16000),
    SCENE_EXPANSION("关键场景展开", "为场景清单中的关键场景补充冲突、行动、人物互动、转折与后果，提供可直接用于正文的底稿；不扩写成完整正文，不更换已确定的核心事件或结果。", 16000);

    private final String label;
    private final String instruction;
    private final int maxTokens;
    SnowflakeStep(String label, String instruction, int maxTokens) {
        this.label = label; this.instruction = instruction; this.maxTokens = maxTokens;
    }
    public String label() { return label; }
    public String instruction() { return instruction; }
    public int maxTokens() { return maxTokens; }
}

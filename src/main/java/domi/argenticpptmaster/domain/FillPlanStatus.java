package domi.argenticpptmaster.domain;

/** 模板填充计划在工作区中的推导状态。 */
public enum FillPlanStatus {
    NONE,
    DRAFT,
    CONFIRMED,
    VALIDATED;

    public String value() {
        return name();
    }
}

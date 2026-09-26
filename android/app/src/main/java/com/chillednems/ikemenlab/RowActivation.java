package com.chillednems.ikemenlab;

/** Decides what an activation of a library row means. */
final class RowActivation {
    enum Action { SELECT, TOGGLE, IGNORE }

    static Action decide(String selectedKey, String activatedKey, boolean busy) {
        if (busy) return Action.IGNORE;
        return activatedKey.equals(selectedKey) ? Action.TOGGLE : Action.SELECT;
    }

    private RowActivation() { }
}

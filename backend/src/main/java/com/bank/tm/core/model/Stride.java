package com.bank.tm.core.model;

public enum Stride {
    SPOOFING("S"), TAMPERING("T"), REPUDIATION("R"), INFORMATION_DISCLOSURE("I"), DENIAL_OF_SERVICE("D"),
    ELEVATION_OF_PRIVILEGE("E");

    private final String letter;

    Stride(String letter) { this.letter = letter; }

    public String letter() { return letter; }
}

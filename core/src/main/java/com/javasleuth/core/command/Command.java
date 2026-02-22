package com.javasleuth.core.command;

public interface Command {
    String execute(String[] args) throws Exception;
    String getDescription();
}

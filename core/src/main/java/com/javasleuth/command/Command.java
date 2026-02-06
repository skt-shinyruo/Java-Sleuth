package com.javasleuth.command;

public interface Command {
    String execute(String[] args) throws Exception;
    String getDescription();
}
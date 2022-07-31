package com.sonarQube.test.service;

import org.springframework.stereotype.Service;

@Service
public class MessageServiceImpl {
	
	public String getMessage() {
		String str = "hello";
		Object obj = getObject();
		System.out.println(obj.toString());
		return str;
	}
	
	private Object getObject() {
		return null;
	}

}

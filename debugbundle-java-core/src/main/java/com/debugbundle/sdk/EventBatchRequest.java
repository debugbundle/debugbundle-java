package com.debugbundle.sdk;

import java.util.List;
import java.util.Map;

record EventBatchRequest(List<Map<String, Object>> events) {
}


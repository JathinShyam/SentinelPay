package com.sentinelpay.wallet.api;

import java.util.List;

public record ActivityPage(List<ActivityItem> items, String nextCursor) {
}

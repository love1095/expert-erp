package com.experterp.backend.integration.xero;

import com.experterp.backend.integration.xero.XeroApi.Session;

/**
 * Phase L2: sends one kind of queued item to Xero and returns the Xero ID it got. The queue gives a session whose
 * Idempotency-Key is the item's key, so a send repeated after a crash does not create a second document in Xero.
 */
public interface XeroItemSender {

    /** The item kinds this sender handles (CONTACT, INVOICE…). */
    java.util.Set<String> kinds();

    /** Sends the item (its key, e.g. CONTACT:CUSTOMER:5) and returns its Xero ID. Throws XeroException or IllegalStateException. */
    String send(Session session, XeroSyncService.Item item);
}

package com.experterp.backend.integration.xero;

import com.experterp.backend.integration.xero.XeroApi.JournalLine;
import com.experterp.backend.integration.xero.XeroApi.ManualJournal;
import com.experterp.backend.integration.xero.XeroApi.Session;
import com.experterp.backend.service.AccountingExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Phase L3: COGS and stock journals at FIFO cost to Xero as manual journals, as the CSV export's ManualJournals file. */
@Component
@RequiredArgsConstructor
public class XeroJournalSender implements XeroItemSender {

    private final XeroClient xero;
    private final AccountingExportService export;

    @Override
    public Set<String> kinds() { return Set.of("JOURNAL"); }

    @Override
    public String send(Session s, XeroSyncService.Item item) {
        AccountingExportService.Journal j = export.journal(item.key())
                .orElseThrow(() -> new IllegalArgumentException("The stock movement behind this journal is no longer there"));
        return xero.saveManualJournal(s, new ManualJournal(null, j.narration(), j.date().toString(), "POSTED", List.of(
                new JournalLine(j.amount(), j.debit(), j.narration(), "NONE"),
                new JournalLine(j.amount().negate(), j.credit(), j.narration(), "NONE")))).manualJournalID();
    }
}

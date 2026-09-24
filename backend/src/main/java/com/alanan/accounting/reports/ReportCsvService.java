package com.alanan.accounting.reports;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportCsvService {
    private static final int MAX_ROWS=10_000;
    private static final Map<String,List<String>> COLUMNS=Map.of(
        "sales",List.of("invoice_date","invoice_number","customer_name","status","total","paid","outstanding"),
        "purchases",List.of("invoice_date","invoice_number","supplier_name","status","total","paid","outstanding"),
        "expenses",List.of("expense_date","category_name","payment_method_name","amount","notes"),
        "suppliers",List.of("name","purchased","paid","current_outstanding"),
        "profit",List.of("invoice_date","invoice_number","customer_name","known_revenue","known_cost","unknown_cost_revenue","known_gross_profit"));
    private final ReportQueries queries;
    public ReportCsvService(ReportQueries queries) { this.queries=queries; }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    @PreAuthorize("isAuthenticated()")
    public byte[] export(String kind,LocalDate from,LocalDate to,AuthUser user) {
        var columns=COLUMNS.get(kind);
        if(columns==null) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND");
        var csv=new StringBuilder("\uFEFF");
        csv.append(String.join(",",columns)).append("\r\n");
        int count=0;
        for(int page=0;;page++) {
            var rows=queries.report(kind,from,to,page,user).rows();
            for(var row:rows.items()) {
                if(++count>MAX_ROWS) throw new ApiException(HttpStatus.BAD_REQUEST,"EXPORT_TOO_LARGE");
                csv.append(columns.stream().map(column -> field(row.get(column))).reduce((a,b) -> a+","+b).orElse(""))
                    .append("\r\n");
            }
            if(!rows.hasMore()) break;
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String field(Object raw) {
        String value=Objects.toString(raw,"");
        // Spreadsheet applications execute cells beginning with formula operators.
        String leading=value.stripLeading();
        if(!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0))>=0 && !leading.matches("-?[0-9]+([.][0-9]+)?")) value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
}

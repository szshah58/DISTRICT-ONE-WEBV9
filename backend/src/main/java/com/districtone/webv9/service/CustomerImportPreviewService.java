package com.districtone.webv9.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class CustomerImportPreviewService {
  private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
  private static final long MAX_TOTAL_BYTES = 40L * 1024 * 1024;
  private static final int MAX_ROWS = 10_000;
  private static final int MAX_ZIP_UNCOMPRESSED_BYTES = 80 * 1024 * 1024;
  private static final int MAX_ZIP_ENTRY_BYTES = 40 * 1024 * 1024;
  private static final List<String> REQUIRED_FIELDS = List.of("REG No.", "MBR No.", "Customer Name", "CNIC");

  private final JdbcTemplate jdbc;

  public CustomerImportPreviewService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Totals(int existingMatch, int newRecords, int duplicates, int mismatches,
                       int missingRequiredInformation, int cannotBeMapped) {}
  public record PreviewRecord(String classification, String sourceFiles, String sourceRows,
                              String mbrNo, String regNo, String customerName, String cnic,
                              String contactInformation, String plotUnitInformation, String block,
                              String category, String type, String instalmentInformation,
                              String developmentCharges, String databaseComparison) {}
  public record PreviewResult(int filesReviewed, int sourceRowsReviewed, int recordGroups,
                              Totals totals, List<PreviewRecord> records,
                              boolean databaseChangesMade) {}

  private enum Field {
    MBR_NO("mbrNo"), REG_NO("regNo"), NAME("customerName"), CNIC("cnic"),
    CONTACT("contactInformation"), PLOT("plotUnitInformation"), BLOCK("block"),
    CATEGORY("category"), TYPE("type"), PLOT_SIZE("plotUnitInformation"),
    INSTALMENT("instalmentInformation"), DEVELOPMENT("developmentCharges"),
    ADDRESS("contactInformation");
    final String key;
    Field(String key) { this.key = key; }
  }

  private record SourceRow(String file, int row, Map<Field, String> values,
                           boolean mapped, boolean registrationColumn, boolean masterRow) {}
  private record Existing(String mbrNo, String name, String cnic, String contact,
                          String plotNo, String block, String category, String type) {}

  @Transactional(readOnly = true)
  public PreviewResult preview(List<MultipartFile> files) {
    if (files == null || files.isEmpty()) throw badRequest("Select at least one .xlsx or .csv source file.");
    long totalBytes = 0;
    for (MultipartFile file : files) {
      if (file == null || file.isEmpty()) throw badRequest("One of the selected files is empty.");
      totalBytes += file.getSize();
      if (file.getSize() > MAX_FILE_BYTES) throw badRequest("Each source file must be 20 MB or smaller.");
      String name = safeName(file.getOriginalFilename());
      if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx") && !name.toLowerCase(Locale.ROOT).endsWith(".csv"))
        throw badRequest("Only .xlsx and .csv source files can be previewed. PDF plan documents are reference material and are not customer-row data.");
    }
    if (totalBytes > MAX_TOTAL_BYTES) throw badRequest("The selected source files must total 40 MB or less.");

    List<SourceRow> sourceRows = new ArrayList<>();
    for (MultipartFile file : files) {
      try {
        String name = safeName(file.getOriginalFilename());
        List<List<String>> rows = name.toLowerCase(Locale.ROOT).endsWith(".csv")
            ? readCsv(file.getBytes()) : readXlsx(file.getBytes());
        sourceRows.addAll(mapRows(name, rows));
      } catch (ResponseStatusException e) {
        throw e;
      } catch (Exception e) {
        throw badRequest("Could not read " + safeName(file.getOriginalFilename()) + ". Check that it is a valid .xlsx or UTF-8 .csv file.");
      }
      if (sourceRows.size() > MAX_ROWS) throw badRequest("Preview supports up to 10,000 source rows at a time.");
    }

    Map<String, Aggregate> grouped = new LinkedHashMap<>();
    List<PreviewRecord> records = new ArrayList<>();
    for (SourceRow row : sourceRows) {
      if (!row.mapped() || !row.registrationColumn()) {
        records.add(toRecord(row.mapped() ? "Cannot be mapped" : "Cannot be mapped", row,
            row.registrationColumn() ? "No recognized customer columns were found." : "No REG No. column was recognized; REG No. is required for database matching."));
        continue;
      }
      String reg = clean(row.values().get(Field.REG_NO));
      if (reg == null) {
        records.add(toRecord("Missing required information", row,
            "Missing REG No.; required fields: " + String.join(", ", REQUIRED_FIELDS) + "."));
        continue;
      }
      String key = normalizeKey(reg);
      grouped.computeIfAbsent(key, ignored -> new Aggregate(reg)).add(row);
    }

    Map<String, Existing> existing = findExisting(grouped.values().stream().map(a -> a.regNo).toList());
    List<Aggregate> ordered = new ArrayList<>(grouped.values());
    ordered.sort(Comparator.comparing(a -> normalizeKey(a.regNo)));
    for (Aggregate aggregate : ordered) records.add(classify(aggregate, existing.get(normalizeKey(aggregate.regNo))));

    if (records.size() > MAX_ROWS) throw badRequest("Preview supports up to 10,000 preview rows at a time.");
    int matched = 0, newRows = 0, duplicates = 0, mismatches = 0, missing = 0, unmappable = 0;
    for (PreviewRecord record : records) {
      switch (record.classification()) {
        case "Existing / Match" -> matched++;
        case "New" -> newRows++;
        case "Duplicate" -> duplicates++;
        case "Mismatch" -> mismatches++;
        case "Missing required information" -> missing++;
        default -> unmappable++;
      }
    }
    return new PreviewResult(files.size(), sourceRows.size(), records.size(),
        new Totals(matched, newRows, duplicates, mismatches, missing, unmappable), records, false);
  }

  private Map<String, Existing> findExisting(Collection<String> registrationNumbers) {
    Map<String, Existing> result = new HashMap<>();
    List<String> values = registrationNumbers.stream().map(CustomerImportPreviewService::clean)
        .filter(v -> v != null).distinct().toList();
    for (int start = 0; start < values.size(); start += 400) {
      List<String> chunk = values.subList(start, Math.min(values.size(), start + 400));
      String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
      jdbc.query("""
          SELECT r.reg_no, m.mbr_no, COALESCE(NULLIF(m.name, ''), r.source_name) AS customer_name,
                 COALESCE(NULLIF(m.cnic_no, ''), r.source_cnic_no) AS cnic,
                 COALESCE(NULLIF(m.contact1, ''), r.source_contact1) AS contact,
                 p.plot_no, b.block_name, COALESCE(NULLIF(r.category, ''), p.category) AS category,
                 p.plot_type
          FROM registrations r
          JOIN members m ON m.member_id = r.member_id
          LEFT JOIN sales_bookings sb ON sb.registration_id = r.registration_id
          LEFT JOIN plots p ON p.plot_id = sb.plot_id
          LEFT JOIN blocks b ON b.block_id = p.block_id
          WHERE r.reg_no IN (""" + placeholders + ") ORDER BY r.registration_id DESC, sb.sale_booking_id DESC",
          rs -> {
            while (rs.next()) {
            String reg = rs.getString("reg_no");
            result.putIfAbsent(normalizeKey(reg), new Existing(rs.getString("mbr_no"), rs.getString("customer_name"),
                rs.getString("cnic"), rs.getString("contact"), rs.getString("plot_no"),
                rs.getString("block_name"), rs.getString("category"), rs.getString("plot_type")));
            }
            return null;
          }, chunk.toArray());
    }
    return result;
  }

  private PreviewRecord classify(Aggregate a, Existing existing) {
    String missing = REQUIRED_FIELDS.stream().filter(label -> switch (label) {
      case "REG No." -> clean(a.value(Field.REG_NO)) == null;
      case "MBR No." -> clean(a.value(Field.MBR_NO)) == null;
      case "Customer Name" -> clean(a.value(Field.NAME)) == null;
      case "CNIC" -> clean(a.value(Field.CNIC)) == null;
      default -> false;
    }).reduce((left, right) -> left + ", " + right).orElse(null);
    if (missing != null) return a.toRecord("Missing required information", "Missing: " + missing + ".");
    if (a.duplicate) return a.toRecord("Duplicate", "REG No. appears more than once in a customer-master source file.");
    String mbrNo = a.value(Field.MBR_NO), name = a.value(Field.NAME), cnic = a.value(Field.CNIC);
    String contact = a.value(Field.CONTACT), plot = a.value(Field.PLOT), block = a.value(Field.BLOCK);
    String category = a.value(Field.CATEGORY), type = a.value(Field.TYPE);
    List<String> mismatches = new ArrayList<>(a.conflicts);
    if (existing == null) return a.toRecord(mismatches.isEmpty() ? "New" : "Mismatch",
        mismatches.isEmpty() ? "REG No. was not found in registrations." : "Conflicting source values: " + String.join(", ", new LinkedHashSet<>(mismatches)) + "; REG No. was not found in registrations.");
    compare(mismatches, "MBR No.", mbrNo, existing.mbrNo());
    compare(mismatches, "Customer Name", name, existing.name());
    compare(mismatches, "CNIC", cnic, existing.cnic());
    compare(mismatches, "Contact", contact, existing.contact());
    compare(mismatches, "Plot/Unit", plot, existing.plotNo());
    compare(mismatches, "Block", block, existing.block());
    compare(mismatches, "Category", category, existing.category());
    compare(mismatches, "Type", type, existing.type());
    String comparison = mismatches.isEmpty()
        ? "REG No. matched; supplied comparable fields match the database."
        : "REG No. matched; check: " + String.join(", ", new LinkedHashSet<>(mismatches)) + ".";
    return a.toRecord(mismatches.isEmpty() ? "Existing / Match" : "Mismatch", comparison);
  }

  private void compare(List<String> mismatches, String label, String source, String database) {
    String left = clean(source), right = clean(database);
    if (left != null && right != null && !sameValue(left, right)) mismatches.add(label);
  }

  private List<SourceRow> mapRows(String filename, List<List<String>> sheetRows) {
    boolean detailFile = normalizeHeader(filename).contains("INSTALMENT") || normalizeHeader(filename).contains("INSTALLMENT");
    boolean developmentFile = normalizeHeader(filename).contains("DC") || normalizeHeader(filename).contains("DEVELOPMENT");
    int headerIndex = -1;
    Map<Integer, Field> columns = Map.of();
    boolean registrationColumn = false;
    int maxHeader = Math.min(sheetRows.size(), 20);
    for (int i = 0; i < maxHeader; i++) {
      Map<Integer, Field> candidate = new HashMap<>();
      boolean hasReg = false;
      List<String> cells = sheetRows.get(i);
      for (int col = 0; col < cells.size(); col++) {
        Field field = mapHeader(cells.get(col));
        if (field == null && developmentFile && "STATUS".equals(normalizeHeader(cells.get(col)))) field = Field.DEVELOPMENT;
        if (field != null) {
          candidate.put(col, field);
          if (field == Field.REG_NO) hasReg = true;
        }
      }
      if (candidate.size() > columns.size()) {
        headerIndex = i; columns = candidate; registrationColumn = hasReg;
      }
      if (hasReg && candidate.size() >= 2) { headerIndex = i; columns = candidate; registrationColumn = true; break; }
    }
    if (headerIndex < 0) {
      List<SourceRow> unmapped = new ArrayList<>();
      for (int i = 1; i < sheetRows.size(); i++) {
        if (sheetRows.get(i).stream().anyMatch(v -> clean(v) != null))
          unmapped.add(new SourceRow(filename, i + 1, Map.of(), false, false, false));
      }
      return unmapped;
    }
    boolean mapped = !columns.isEmpty();
    List<SourceRow> result = new ArrayList<>();
    for (int i = headerIndex + 1; i < sheetRows.size(); i++) {
      List<String> cells = sheetRows.get(i);
      if (cells.stream().allMatch(v -> clean(v) == null)) continue;
      EnumMap<Field, String> values = new EnumMap<>(Field.class);
      for (Map.Entry<Integer, Field> column : columns.entrySet()) {
        int index = column.getKey();
        String value = index < cells.size() ? clean(cells.get(index)) : null;
        if (value == null) continue;
        Field field = column.getValue();
        if (field == Field.INSTALMENT && developmentFile) field = Field.DEVELOPMENT;
        if (field == Field.CATEGORY && developmentFile && normalizeHeader(sheetRows.get(headerIndex).get(index)).contains("INSTALLMENT")) field = Field.DEVELOPMENT;
        if (field == Field.PLOT_SIZE) values.merge(Field.PLOT, value, CustomerImportPreviewService::joinDistinct);
        else if (field == Field.INSTALMENT || field == Field.DEVELOPMENT || field == Field.CONTACT || field == Field.ADDRESS) {
          String header = index < sheetRows.get(headerIndex).size() ? clean(sheetRows.get(headerIndex).get(index)) : null;
          String detail = (header == null ? "" : header + ": ") + value;
          values.merge(field, detail, CustomerImportPreviewService::joinDistinct);
        } else values.merge(field, value, CustomerImportPreviewService::joinDistinct);
      }
      boolean identityRow = !detailFile && (values.containsKey(Field.NAME) || values.containsKey(Field.CNIC));
      result.add(new SourceRow(filename, i + 1, values, mapped, registrationColumn, identityRow));
    }
    return result;
  }

  private Field mapHeader(String header) {
    String value = normalizeHeader(header);
    if (value.isEmpty()) return null;
    if (Set.of("MBRNO", "MBRNUMBER", "MEMNO", "MEMBERNO", "MEMBERNUMBER").contains(value)) return Field.MBR_NO;
    if (Set.of("REGNO", "REGNUMBER", "REGISTRATIONNO", "REGISTRATIONNUMBER", "SALENO", "SALENUMBER").contains(value)) return Field.REG_NO;
    if (Set.of("NAME", "CUSTOMERNAME", "PARTYNAME", "FULLNAME").contains(value)) return Field.NAME;
    if (Set.of("CNIC", "CNICNO", "NIC", "NICNO", "IDCARD", "IDCARDNO").contains(value)) return Field.CNIC;
    if (Set.of("CONTACT1", "CONTACT", "CONTACTNO", "PHONE", "PHONENO", "MOBILE", "MOBILENO", "PARTYCONTACT", "LANDLINE1", "LANDLINE2", "CONTACT2", "MOBILE2", "EMAIL1", "EMAIL2").contains(value)) return Field.CONTACT;
    if (Set.of("PLOTNO", "PLOTNUMBER", "UNITNO", "UNITNUMBER", "AUTOUNITNO", "UNITCODE").contains(value)) return Field.PLOT;
    if (Set.of("BLOCK", "BLOCKNAME", "BOCK").contains(value)) return Field.BLOCK;
    if (Set.of("CATEGORY", "CATEGORYTYPE").contains(value)) return Field.CATEGORY;
    if (Set.of("TYPE", "PLOTTYPE").contains(value)) return Field.TYPE;
    if (Set.of("PLOTSIZE", "PLOTSIZEMARLA", "SIZEMARLA", "MARLA").contains(value)) return Field.PLOT_SIZE;
    if (value.startsWith("INST") || value.startsWith("INSTALL") || value.startsWith("INSTAL")
        || Set.of("BALANCE", "ADJUSTMENT", "DISCOUNT", "RECEIPT").contains(value)) return Field.INSTALMENT;
    if (value.startsWith("DC") || value.startsWith("DEVELOPMENT")) return Field.DEVELOPMENT;
    if (Set.of("ADDRESS", "ADDRESS1", "ADDRESS2", "CITY", "COUNTRY").contains(value)) return Field.ADDRESS;
    return null;
  }

  private List<List<String>> readCsv(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.UTF_8);
    if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (quoted) {
        if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
        else if (ch == '"') quoted = false;
        else cell.append(ch);
      } else if (ch == '"' && cell.length() == 0) quoted = true;
      else if (ch == ',') { row.add(cell.toString()); cell.setLength(0); }
      else if (ch == '\n' || ch == '\r') {
        if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        row.add(cell.toString()); cell.setLength(0); rows.add(row); row = new ArrayList<>();
        if (rows.size() > MAX_ROWS + 20) throw badRequest("Preview supports up to 10,000 source rows at a time.");
      } else cell.append(ch);
    }
    if (quoted) throw badRequest("The CSV file contains an unclosed quoted value.");
    if (!row.isEmpty() || cell.length() > 0) { row.add(cell.toString()); rows.add(row); }
    return rows;
  }

  private List<List<String>> readXlsx(byte[] bytes) throws Exception {
    Map<String, byte[]> entries = unzip(bytes);
    byte[] sheet = entries.get("xl/worksheets/sheet1.xml");
    if (sheet == null) throw badRequest("The workbook has no first worksheet.");
    List<String> shared = entries.containsKey("xl/sharedStrings.xml") ? readSharedStrings(entries.get("xl/sharedStrings.xml")) : List.of();
    return readWorksheet(sheet, shared);
  }

  private Map<String, byte[]> unzip(byte[] bytes) throws IOException {
    Map<String, byte[]> entries = new HashMap<>();
    int total = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        if (name.startsWith("/") || name.contains("..")) throw badRequest("The workbook contains an invalid entry path.");
        boolean needed = name.equals("xl/sharedStrings.xml") || name.equals("xl/worksheets/sheet1.xml");
        if (needed) {
          byte[] contents = readLimited(zip, MAX_ZIP_ENTRY_BYTES);
          total += contents.length;
          if (total > MAX_ZIP_UNCOMPRESSED_BYTES) throw badRequest("The workbook expands beyond the preview safety limit.");
          entries.put(name, contents);
        }
      }
    }
    return entries;
  }

  private byte[] readLimited(java.io.InputStream input, int maximum) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if (output.size() + read > maximum) throw badRequest("A workbook component exceeds the preview safety limit.");
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private List<String> readSharedStrings(byte[] xml) throws Exception {
    List<String> strings = new ArrayList<>();
    XMLStreamReader reader = xmlReader(xml);
    StringBuilder value = null;
    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT && "si".equals(reader.getLocalName())) value = new StringBuilder();
      else if (event == XMLStreamConstants.START_ELEMENT && "t".equals(reader.getLocalName()) && value != null) value.append(reader.getElementText());
      else if (event == XMLStreamConstants.END_ELEMENT && "si".equals(reader.getLocalName()) && value != null) { strings.add(value.toString()); value = null; }
    }
    reader.close();
    return strings;
  }

  private List<List<String>> readWorksheet(byte[] xml, List<String> shared) throws Exception {
    List<List<String>> rows = new ArrayList<>();
    XMLStreamReader reader = xmlReader(xml);
    List<String> row = null;
    int col = -1;
    String cellType = null;
    StringBuilder value = null;
    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        String tag = reader.getLocalName();
        if ("row".equals(tag)) row = new ArrayList<>();
        else if ("c".equals(tag) && row != null) {
          col = columnIndex(reader.getAttributeValue(null, "r"));
          cellType = reader.getAttributeValue(null, "t");
          value = new StringBuilder();
        } else if (("v".equals(tag) || "t".equals(tag)) && value != null) value.append(reader.getElementText());
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        String tag = reader.getLocalName();
        if ("c".equals(tag) && row != null && value != null && col >= 0) {
          String cell = value.toString();
          if ("s".equals(cellType)) {
            try { cell = shared.get(Integer.parseInt(cell)); } catch (RuntimeException ignored) { cell = ""; }
          }
          while (row.size() <= col) row.add("");
          row.set(col, cell);
          col = -1; cellType = null; value = null;
        } else if ("row".equals(tag) && row != null) {
          rows.add(row); row = null;
          if (rows.size() > MAX_ROWS + 20) throw badRequest("Preview supports up to 10,000 source rows at a time.");
        }
      }
    }
    reader.close();
    return rows;
  }

  private XMLStreamReader xmlReader(byte[] xml) throws Exception {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    return factory.createXMLStreamReader(new ByteArrayInputStream(xml));
  }

  private int columnIndex(String reference) {
    if (reference == null) return -1;
    int index = 0;
    boolean seen = false;
    for (char ch : reference.toCharArray()) {
      if (ch >= 'A' && ch <= 'Z') { index = index * 26 + ch - 'A' + 1; seen = true; }
      else if (ch >= 'a' && ch <= 'z') { index = index * 26 + ch - 'a' + 1; seen = true; }
      else break;
    }
    return seen ? index - 1 : -1;
  }

  private PreviewRecord toRecord(String classification, SourceRow row, String comparison) {
    return new PreviewRecord(classification, row.file(), Integer.toString(row.row()),
        row.values().get(Field.MBR_NO), row.values().get(Field.REG_NO), row.values().get(Field.NAME),
        row.values().get(Field.CNIC), row.values().get(Field.CONTACT), row.values().get(Field.PLOT),
        row.values().get(Field.BLOCK), row.values().get(Field.CATEGORY), row.values().get(Field.TYPE),
        row.values().get(Field.INSTALMENT), row.values().get(Field.DEVELOPMENT), comparison);
  }

  private String normalizeHeader(String value) {
    if (value == null) return "";
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return normalized.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
  }

  private static String normalizeKey(String value) {
    return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
  }

  private static boolean sameValue(String left, String right) {
    if (left.equals(right)) return true;
    String a = normalizeKey(left), b = normalizeKey(right);
    if (a.equals(b)) return true;
    try { return new BigDecimal(left.replace(",", "")).compareTo(new BigDecimal(right.replace(",", ""))) == 0; }
    catch (NumberFormatException ignored) { return false; }
  }

  private static String safeName(String name) {
    if (name == null || name.isBlank()) return "source file";
    return name.replaceAll("[\\r\\n\\\\/]+", "_");
  }

  private static String clean(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String joinDistinct(String left, String right) {
    if (left == null || left.isBlank()) return right;
    if (right == null || right.isBlank() || Arrays.stream(left.split("\\s*\\|\\s*")).anyMatch(v -> v.equalsIgnoreCase(right))) return left;
    return left + " | " + right;
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private final class Aggregate {
    private final String regNo;
    private final EnumMap<Field, LinkedHashSet<String>> values = new EnumMap<>(Field.class);
    private final LinkedHashSet<String> files = new LinkedHashSet<>();
    private final LinkedHashMap<String, LinkedHashSet<Integer>> sourceRows = new LinkedHashMap<>();
    private final Set<String> conflicts = new LinkedHashSet<>();
    private final Map<String, Integer> masterOccurrences = new HashMap<>();
    private boolean duplicate;

    private Aggregate(String regNo) { this.regNo = regNo; }

    private void add(SourceRow row) {
      files.add(row.file());
      sourceRows.computeIfAbsent(row.file(), ignored -> new LinkedHashSet<>()).add(row.row());
      if (row.masterRow()) {
        int count = masterOccurrences.merge(row.file(), 1, Integer::sum);
        if (count > 1) duplicate = true;
      }
      for (Map.Entry<Field, String> entry : row.values().entrySet()) {
        values.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>()).add(entry.getValue());
      }
    }

    private String value(Field field) {
      Set<String> fieldValues = values.getOrDefault(field, new LinkedHashSet<>());
      if (fieldValues.isEmpty()) return null;
      if (field == Field.INSTALMENT || field == Field.DEVELOPMENT || field == Field.CONTACT || field == Field.ADDRESS) return String.join("; ", fieldValues);
      if (fieldValues.size() > 1) conflicts.add(field.key);
      return String.join(" | ", fieldValues);
    }

    private PreviewRecord toRecord(String classification, String comparison) {
      String rows = sourceRows.entrySet().stream().map(e -> e.getKey() + " #" + String.join(",", e.getValue().stream().map(String::valueOf).toList()))
          .reduce((a, b) -> a + "; " + b).orElse("");
      return new PreviewRecord(classification, String.join(", ", files), rows,
          value(Field.MBR_NO), value(Field.REG_NO), value(Field.NAME), value(Field.CNIC),
          value(Field.CONTACT), value(Field.PLOT), value(Field.BLOCK), value(Field.CATEGORY),
          value(Field.TYPE), value(Field.INSTALMENT), value(Field.DEVELOPMENT), comparison);
    }
  }
}

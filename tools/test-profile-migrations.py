"""Exercise actual production migration SQL offline with SQLite and archived schemas."""
import json, pathlib, re, sqlite3, sys
root = pathlib.Path(__file__).resolve().parent.parent
generated = pathlib.Path(sys.argv[1])
schema_dir = root/'app/schemas/io.nekohasekai.sagernet.database.SagerDatabase'
schemas = {i: json.loads((schema_dir/f'{i}.json').read_text())['database']['entities'] for i in range(1,7)}
manual = (root/'app/src/main/java/io/nekohasekai/sagernet/database/ProfileMigrations.kt').read_text()
steps = {1: re.findall(r'db.execSQL\("([^"]+)"\)', manual.split('val from2To3')[0]),
         2: re.findall(r'db.execSQL\("([^"]+)"\)', manual.split('val from2To3')[1])}
for version in range(3,6):
    file = next(generated.rglob(f'SagerDatabase_AutoMigration_{version}_{version+1}_Impl.java'))
    steps[version] = re.findall(r'db.execSQL\("([^"]+)"\)',file.read_text())
for start in range(1,6):
    db = sqlite3.connect(':memory:')
    for entity in schemas[start]:
        table = entity['tableName']
        db.execute(entity['createSql'].replace('${TABLE_NAME}',table))
        values = {}
        for field in entity['fields']:
            if field['notNull']:
                values[field['columnName']] = 17 if field['affinity']=='INTEGER' else 'synthetic-preserved-value'
        values['id'] = 1
        if table == 'proxy_entities': values['hysteriaBean'] = b'\x00synthetic-credentials\xff'
        columns = ','.join('`'+x+'`' for x in values)
        db.execute(f'INSERT INTO `{table}` ({columns}) VALUES ({",".join("?" for _ in values)})',list(values.values()))
    before = {e['tableName']:db.execute(f'SELECT * FROM `{e["tableName"]}`').fetchall() for e in schemas[start]}
    for version in range(start,6):
        for sql in steps[version]: db.execute(sql)
    for entity in schemas[6]:
        table=entity['tableName']
        actual={r[1]:(r[2], bool(r[3]),r[5]) for r in db.execute(f'PRAGMA table_info(`{table}`)')}
        expected={f['columnName']:(f['affinity'],f['notNull'],1 if f['columnName']=='id' else 0) for f in entity['fields']}
        assert actual == expected,(start,table,actual,expected)
        old_columns=[f['columnName'] for f in next(e for e in schemas[start] if e['tableName']==table)['fields']]
        rows=db.execute(f'SELECT {",".join("`"+c+"`" for c in old_columns)} FROM `{table}`').fetchall()
        assert rows==before[table],(start,table,'historical row or BLOB changed')
    if start == 1:
        assert db.execute('SELECT isSelector,frontProxy,landingProxy FROM proxy_groups').fetchone()==(0,-1,-1)
    print(f'PASS schema {start} -> 6: columns, constraints, original rows/BLOBs preserved')

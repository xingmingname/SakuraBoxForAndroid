"""Prepare isolated host regressions; physical Android networking is explicitly unsupported, never simulated as PASS."""
from pathlib import Path
import argparse,hashlib,json,shutil
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--output',required=True,type=Path)
args=parser.parse_args();target=args.output.resolve();target.mkdir(parents=True,exist_ok=False)
production=['box.go', 'box_include.go', 'config_compat.go', 'config_export.go', 'config_validation.go', 'dns_box.go', 'dns_compat.go', 'dns_dhcp.go', 'dns_outbound_compat.go', 'fix.go', 'geosite_rules.go', 'http.go', 'http_race.go', 'inbound_compat.go', 'interface_monitor.go', 'io.go', 'log_redact.go', 'platform_box.go', 'platform_java.go', 'protect_ack.go', 'urltest_config.go', 'urltest_network.go']
names=sorted(set(production)|{p.name for p in (ROOT/'libcore').glob('*_test.go')})
records=[]
for name in names:
    current=ROOT/'libcore'/name;content=current.read_text(encoding='utf-8');adaptation=None
    if name=='box.go':
        content=content.replace('"github.com/matsuridayo/libneko/protect_server"','')
        content=content[:content.index('func goServeProtect(start bool) {')]
        adaptation='Host only: Unix protect service omitted, explicit error stub supplied.'
    elif name=='platform_box.go':
        assert content.count('syscall.Dup(tunFd)')==1
        content=content.replace('syscall.Dup(tunFd)','hostUnsupportedDup(tunFd)')
        adaptation='Host only: real Android TUN duplication explicitly unavailable.'
    (target/name).write_text(content,encoding='utf-8')
    records.append({'file':name,'sha256':hashlib.sha256(current.read_bytes()).hexdigest(),'adaptation':adaptation})
(target/'host_os_stubs.go').write_text('package libcore\nimport "errors"\nfunc goServeProtect(bool){}\nfunc sendFdToProtect(int,string)error{return errors.New("no protect bridge in host test")}\nfunc hostUnsupportedDup(int)(int,error){return -1,errors.New("Android TUN not executed in host test")}\n',encoding='utf-8')
for name in ['device','ech','procfs']:shutil.copytree(ROOT/'libcore'/name,target/name)
module=(ROOT/'libcore/go.mod').read_text(encoding='utf-8').replace('../.build-deps/libneko',(ROOT/'.build-deps/libneko').as_posix()).replace('../.build-deps/sing-box',(ROOT/'.build-deps/sing-box').as_posix())
(target/'go.mod').write_text(module,encoding='utf-8');shutil.copy2(ROOT/'libcore/go.sum',target/'go.sum')
(target/'host-inputs.json').write_text(json.dumps(records,indent=2),encoding='utf-8')
assert {p.name for p in (ROOT/'libcore').glob('*_test.go')}<={p.name for p in target.glob('*_test.go')}
print(f'Prepared {len(records)} current inputs; all root regression tests included. Android device checks remain unverified.')

import re,csv,json,math,collections
k=open('/tmp/bk/doc.kml').read()
folders=re.findall(r'<Folder>\s*<name>(.*?)</name>(.*?)</Folder>',k,re.S)
feats=[];seen=set();i=0;bad=[];dups=0
def km(c):
    t=0
    for a,b in zip(c,c[1:]):
        dx=(b[0]-a[0])*111320*math.cos(math.radians(a[1]));dy=(b[1]-a[1])*110540;t+=math.hypot(dx,dy)
    return t
kmlkeys=set()
for fn,body in folders:
  for p in re.findall(r'<Placemark>(.*?)</Placemark>',body,re.S):
    g=lambda t:(re.search(rf'<{t}>(.*?)</{t}>',p,re.S) or [None,None])[1]
    cd=lambda s:re.sub(r'<!\[CDATA\[(.*?)\]\]>',r'\1',s or '',flags=re.S).replace('\xa0',' ').strip()
    name=cd(g('name')); desc=cd(g('description'))
    desc=re.sub(r'<br\s*/?>','\n',desc);desc=re.sub(r'<[^>]+>','',desc).replace('&amp;','&').strip()
    sty=g('styleUrl').lstrip('#')
    cs=[tuple(map(float,x.split(',')[:2])) for x in g('coordinates').split()]
    for lo,la in cs:
        if not(-152.7<lo<-152.2 and 57.7<la<57.9): bad.append((name,lo,la))
    key=(name,tuple((round(a,5),round(b,5)) for a,b in cs))
    kmlkeys.add((name,round(cs[0][0],5),round(cs[0][1],5)))
    if key in seen: dups+=1; continue
    seen.add(key);i+=1
    pr={'id':f'bike{i}','name':name or 'Unnamed','src':'Victor — Town Bike Trail Map (Google My Maps)','folder':fn if fn!='Untitled layer' else None}
    if desc: pr['desc']=desc
    if '<LineString>' in p:
        pr.update(k='bikeline',color='#'+re.search(r'line-([0-9A-F]{6})',sty).group(1),len_m=round(km(cs)))
        if not re.match(r'(?i)^(line|path|untitled)\s*\d*$',name): pr['label']=name
        geom={'type':'LineString','coordinates':[list(c) for c in cs]}
    else:
        nm=name.lower()
        kind='head' if re.search(r'trail ?head',nm) else ('mark' if re.match(r'^point \d+$',nm) and not desc else 'work')
        pr.update(k='bikept',kind=kind)
        if not re.match(r'(?i)^point \d+$',name): pr['label']=name[:28]
        geom={'type':'Point','coordinates':list(cs[0])}
    feats.append({'type':'Feature','geometry':geom,'properties':{a:b for a,b in pr.items() if b is not None}})
rows=[r for r in csv.DictReader(open('incoming/Town-Bike-Trail-Map-woods-points.csv')) if r['name']]
miss=[]
for r in rows:
    c=re.findall(r'-?\d+\.\d+',r['WKT']); key=(r['name'].strip(),round(float(c[0]),5),round(float(c[1]),5))
    if key not in kmlkeys: miss.append(key)
L=[f for f in feats if f['properties']['k']=='bikeline'];P=[f for f in feats if f['properties']['k']=='bikept']
print('KML placemarks kept',len(feats),'lines',len(L),'points',len(P),'exact dups dropped',dups)
print('CSV rows',len(rows),'lines',sum(r['WKT'].startswith('LINE') for r in rows),'points',sum(r['WKT'].startswith('POINT') for r in rows),'CSV not in KML',miss)
print('outside town box',len(bad),bad[:5])
allc=[c for f in feats for c in (f['geometry']['coordinates'] if f['geometry']['type']=='LineString' else [f['geometry']['coordinates']])]
print('bbox lon',min(c[0] for c in allc),max(c[0] for c in allc),'lat',min(c[1] for c in allc),max(c[1] for c in allc))
print('miles',round(sum(f['properties']['len_m'] for f in L)/1609.34,1), collections.Counter(f['properties']['kind'] for f in P))
print('named lines',sum('label' in f['properties'] for f in L), sorted({f['properties'].get('label') for f in L if 'label' in f['properties']}))
json.dump({'type':'FeatureCollection','features':feats},open('app/src/main/assets/kodiak/bike_trails.geojson','w'),separators=(',',':'))

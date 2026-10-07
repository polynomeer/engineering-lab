(() => {
  'use strict';
  const button = document.createElement('button');
  button.textContent = '설정 내보내기';
  button.type = 'button';
  button.style.cssText = 'margin:16px;padding:12px;font:inherit;cursor:pointer';
  const status = document.createElement('p'); status.setAttribute('role','status');
  button.onclick = () => {
    try {
      const keys = ['engineering-lab.experiments.dashboard','engineering-lab.experiments.preset-history','engineering-lab.io-history'];
      const local = Object.fromEntries(keys.map(key => [key, localStorage.getItem(key)]));
      const payload = {format:'engineering-lab-legacy',version:1,local,session:{baseline:sessionStorage.getItem('lab.compare.baseline'),candidate:sessionStorage.getItem('lab.compare.candidate')}};
      const raw = JSON.stringify(payload,null,2);
      const blob = new Blob([raw],{type:'application/json'});
      if (blob.size > 1048576) throw new Error('설정이 1 MiB를 초과합니다.');
      const url = URL.createObjectURL(blob), link = document.createElement('a');
      link.href = url; link.download = 'lab-legacy-settings.json'; document.body.appendChild(link); link.click(); link.remove();
      setTimeout(() => URL.revokeObjectURL(url),1000);
      status.textContent = '새 대시보드의 설정 이전에서 이 파일을 가져오세요. 원본 설정은 유지됩니다.';
    } catch (e) { status.textContent = '설정을 읽거나 내보내지 못했습니다: ' + e.message; }
  };
  document.body.append(button,status);
})();

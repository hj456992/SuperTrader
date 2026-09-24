export const jobActive = job => ['running','cancelling'].includes(job?.status);
export function jobForPage(job,ui) {
 if(!job)return null;
 const target=ui.page==='self'?'self':ui.page==='person'?ui.selected:null;
 return target&&job.targetId&&job.targetId!==target?null:job;
}

export const jobIdentity = job => job?.id || JSON.stringify(job);

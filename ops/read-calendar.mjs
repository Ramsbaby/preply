// Executed inside ego-browser nodejs; browser credentials never leave its profile.
const fs = await import('node:fs/promises');
const task = await taskSpace('Preply daily read-only calendar');
try {
  const page = task.page('p1');
  await page.goto('https://preply.com/robots.txt');
  const query = `query TutorCalendarEvents($dateStart:Date!,$dateEnd:Date!,$tzname:String!){
    currentUser { tutor { id calendar(dateStart:$dateStart,dateEnd:$dateEnd,tzname:$tzname){
      nodes { __typename ... on LessonTimeslot {dateStart dateEnd lesson {
        id status price duration earnedAmount isFirstLesson client { user { firstName } }
      }} ... on GroupLessonTimeslot {dateStart} }
    }}}
  }`;
  const result = await page.evaluate(async ({query,start,end,expectedTutor})=>{
    if(location.origin!=='https://preply.com') throw Error('Unexpected Preply origin');
    const response=await fetch('/graphql/v2/TutorCalendarEvents',{
      method:'POST', headers:{'Content-Type':'application/json'},
      body:JSON.stringify({operationName:'TutorCalendarEvents',query,variables:{dateStart:start,dateEnd:end,tzname:'Asia/Seoul'}})
    });
    if(!response.ok) throw Error('Preply read failed: '+response.status);
    const data=await response.json();
    const tutor=data.data?.currentUser?.tutor;
    if(data.errors?.length || !tutor || String(tutor.id)!==expectedTutor || !Array.isArray(tutor.calendar?.nodes)) throw Error('Incomplete calendar or wrong tutor session');
    const days={};
    for(let d=new Date(start+'T00:00:00Z');d.toISOString().slice(0,10)<=end;d.setUTCDate(d.getUTCDate()+1)){
      days[d.toISOString().slice(0,10)]=[];
    }
    for(const n of tutor.calendar.nodes){
      if(n.__typename==='GroupLessonTimeslot') throw Error('Group payout requires separate reconciliation');
      if(n.__typename!=='LessonTimeslot') continue;
      const day=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Seoul'}).format(new Date(n.dateStart));
      if(!Object.hasOwn(days,day)) continue;
      const l=n.lesson;
      days[day].push({id:String(l.id),start:n.dateStart,end:n.dateEnd,student:l.client.user.firstName,status:l.status,price:l.price,duration:l.duration,earned:l.earnedAmount,trial:l.isFirstLesson});
    }
    return days;
  },{query,...collectorOptions});
  const capturedAt=new Date().toISOString();
  for(const [date,lessons] of Object.entries(result)){
    await fs.writeFile(collectorOptions.directory+'/'+date+'.json',JSON.stringify({date,capturedAt,lessons}),{mode:0o600});
  }
} finally {
  await task.finish({keep:[]});
}
